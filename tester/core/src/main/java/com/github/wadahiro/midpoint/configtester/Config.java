package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ExpressionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FunctionLibraryType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScriptExpressionEvaluatorType;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * Changes to the deployed configuration for one test ({@link MidPointConfigTester#config()}): what an operator would
 * change in the deployed object to try a case by hand (a script's limit lowered to a few items), made in the
 * repository instead of the configuration under test, which stays as it is.
 *
 * <pre>{@code
 * t.config().functionLibrary("batch-library").function("nextBatch")
 *         .replace("int BATCH_LIMIT = 300", "int BATCH_LIMIT = 2");
 * }</pre>
 *
 * <p>A change lasts until the test ends (the next test starts from the deployment as loaded) or until a restore to a
 * checkpoint taken before it (in a Kotest spec, a container's prefix: the change made in a container's body holds for
 * its tests). It is written past the model (no audit, no policies, no driver call), through midPoint's repository
 * cache: the next evaluation runs the changed code. Every change reads back what it wrote and fails
 * ({@link IllegalStateException}) if the repository does not have it. {@code Run.select()} and the mapping back to the
 * configuration files see the changed code, not the file's.</p>
 */
public final class Config {

    private final MidPointConfigTester tester;

    Config(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** The deployed function library by name (or OID); fails if there is none. */
    public FunctionLibrary functionLibrary(String name) {
        return new FunctionLibrary(name);
    }

    /** A deployed function library ({@link #functionLibrary(String)}). */
    public final class FunctionLibrary {

        private final String name;

        private FunctionLibrary(String name) {
            this.name = name;
            existing();
        }

        /** The library's function by name (unique in a library); fails if there is none. */
        public LibraryFunction function(String function) {
            LibraryFunction found = new LibraryFunction(this, function);
            found.script(existing());
            return found;
        }

        private FunctionLibraryType existing() {
            FunctionLibraryType library = api().find(FunctionLibraryType.class, name);
            if (library == null) {
                throw new IllegalArgumentException("config: no function library " + name);
            }
            return library;
        }

        @Override
        public String toString() {
            return "function library " + name;
        }
    }

    /** A function of a deployed library whose code is a script ({@link FunctionLibrary#function(String)}). */
    public final class LibraryFunction {

        private final FunctionLibrary library;
        private final String name;

        private LibraryFunction(FunctionLibrary library, String name) {
            this.library = library;
            this.name = name;
        }

        /** The function's script code as midPoint has it now (changed by this test, if it was). */
        public String code() {
            return script(library.existing()).getCode();
        }

        /**
         * Replaces the text in the function's script code by the replacement. The text must occur exactly once, so a
         * change of the configuration that moves or rewrites it fails the test instead of leaving the code unchanged.
         */
        public LibraryFunction replace(String text, String replacement) {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("config: replace needs the text to replace");
            }
            FunctionLibraryType stored = library.existing();
            String code = script(stored).getCode();
            int occurrences = occurrences(code, text);
            if (occurrences != 1) {
                throw new IllegalArgumentException("config: \"" + text + "\" occurs " + occurrences + " times in "
                        + this + " (exactly once needed); its code:\n" + code);
            }
            String changed = code.replace(text, replacement);
            FunctionLibraryType copy = stored.clone();
            script(copy).setCode(changed);
            List<ItemDelta<?, ?>> deltas;
            try {
                deltas = new ArrayList<>(api().prismContext().deltaFor(FunctionLibraryType.class)
                        .item(FunctionLibraryType.F_FUNCTION).replace(copy.getFunction().toArray())
                        .asItemDeltas());
            } catch (SchemaException e) {
                throw new IllegalStateException("config: " + e.getMessage(), e);
            }
            api().modifyConfigurationRaw(FunctionLibraryType.class, stored.getOid(), deltas);
            String now = code();
            if (!changed.equals(now)) {
                throw new IllegalStateException("config: replaced \"" + text + "\" in " + this
                        + ", but the repository has:\n" + now);
            }
            return this;
        }

        /** The function's script evaluator in this copy of the library. */
        private ScriptExpressionEvaluatorType script(FunctionLibraryType in) {
            List<ExpressionType> functions = in.getFunction().stream().filter(f -> name.equals(f.getName()))
                    .collect(Collectors.toList());
            if (functions.isEmpty()) {
                throw new IllegalArgumentException("config: no function " + name + " in " + library + "; it has "
                        + in.getFunction().stream().map(ExpressionType::getName).collect(Collectors.toList()));
            }
            List<ScriptExpressionEvaluatorType> scripts = new ArrayList<>();
            // JAXBElements: javax.xml.bind on 4.0 and 4.4, jakarta.xml.bind on 4.8 and later
            for (Object evaluator : functions.get(0).getExpressionEvaluator()) {
                Object value = elementValue(evaluator);
                if (value instanceof ScriptExpressionEvaluatorType) {
                    scripts.add((ScriptExpressionEvaluatorType) value);
                }
            }
            if (scripts.size() != 1) {
                throw new IllegalArgumentException("config: " + this + " has " + scripts.size()
                        + " script evaluators (one needed)");
            }
            return scripts.get(0);
        }

        @Override
        public String toString() {
            return "function " + name + " of " + library;
        }
    }

    private static Object elementValue(Object element) {
        try {
            return element.getClass().getMethod("getValue").invoke(element);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("config: cannot read an expression evaluator " + element, e);
        }
    }

    private static int occurrences(String code, String text) {
        int count = 0;
        for (int at = code.indexOf(text); at >= 0; at = code.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    private MidPoint api() {
        return tester.deployment().api();
    }
}
