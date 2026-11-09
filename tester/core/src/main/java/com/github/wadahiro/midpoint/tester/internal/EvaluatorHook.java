package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.scan.EvaluatorFingerprint;

/**
 * Sees every expression evaluator midPoint runs, whatever its kind ({@code script}, {@code path},
 * {@code assignmentTargetSearch}, ...), nested ones (search filters, function arguments, library functions) included.
 *
 * {@link #install} replaces each evaluator factory registered in midPoint's {@code ExpressionFactory} with a proxy of
 * the same interfaces. When the factory creates an evaluator, the proxy takes the canonical form of the evaluator
 * elements ({@link EvaluatorFingerprint}, from the line's prism serialization); when the evaluator runs, the
 * {@link Listener} is told before and after. The factories and evaluators are only reached through midPoint's
 * interfaces, so the proxies change nothing else. The {@code ExpressionFactory} fields are read by name
 * ({@code evaluatorFactoriesMap}, {@code defaultEvaluatorFactory}, the same on every supported line).
 *
 * midPoint caches compiled expressions by their content: identical expressions in two places share one evaluator,
 * so the evaluator alone cannot tell where it is configured (the mapping it runs in can, see {@link Recorder}).
 */
public final class EvaluatorHook {

    /** Told about every evaluation; must not throw. */
    public interface Listener {
        /** Before {@code evaluate(args)}; returns what {@link #after} gets back. */
        Object before(String fingerprint, Object[] args);

        void after(Object token, Object output, Throwable error);
    }

    private static volatile Listener listener;
    /** The local names of the evaluator elements the hooked factories handle. */
    private static final Set<String> AVAILABLE = Collections.synchronizedSet(new TreeSet<>());

    private EvaluatorHook() {
    }

    /**
     * The evaluator elements midPoint of this line has a factory for ({@code script}, {@code path}, ...); an expression
     * whose first evaluator element is not among them cannot be evaluated. Empty before midPoint started.
     */
    public static Set<String> availableEvaluators() {
        synchronized (AVAILABLE) {
            return Set.copyOf(AVAILABLE);
        }
    }

    public static void listener(Listener newListener) {
        listener = newListener;
    }

    /**
     * Wraps the evaluator factories of the {@code ExpressionFactory}.
     *
     * @param expressionFactory midPoint's {@code ExpressionFactory}, with its factories registered
     * @param serializer the line's prism serialization of an evaluator element (a {@code JAXBElement}) to XML
     */
    @SuppressWarnings("unchecked")
    public static void install(Object expressionFactory, Function<Object, String> serializer) {
        try {
            Map<Object, Object> factories = (Map<Object, Object>) field(expressionFactory, "evaluatorFactoriesMap")
                    .get(expressionFactory);
            for (Map.Entry<Object, Object> entry : factories.entrySet()) {
                entry.setValue(wrapFactory(entry.getValue(), serializer));
                if (entry.getKey() instanceof QName) {
                    AVAILABLE.add(((QName) entry.getKey()).getLocalPart());
                }
            }
            Field defaultFactory = field(expressionFactory, "defaultEvaluatorFactory");
            Object original = defaultFactory.get(expressionFactory);
            if (original != null) {
                defaultFactory.set(expressionFactory, wrapFactory(original, serializer));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot hook the evaluators of " + expressionFactory, e);
        }
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name + " in " + target.getClass());
    }

    private static Object wrapFactory(Object factory, Function<Object, String> serializer) {
        if (Proxy.isProxyClass(factory.getClass()) && Proxy.getInvocationHandler(factory) instanceof FactoryHandler) {
            return factory;
        }
        return proxy(factory, new FactoryHandler(factory, serializer));
    }

    private static Object proxy(Object target, InvocationHandler handler) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            collectInterfaces(type, interfaces);
        }
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces.toArray(new Class<?>[0]),
                handler);
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
        for (Class<?> each : type.getInterfaces()) {
            if (interfaces.add(each)) {
                collectInterfaces(each, interfaces);
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The fingerprint of the evaluator elements an expression hands to its factory, or null if there are none. */
    static String fingerprint(Collection<?> evaluatorElements, Function<Object, String> serializer) {
        if (evaluatorElements == null || evaluatorElements.isEmpty()) {
            return null;
        }
        List<String> forms = new ArrayList<>();
        for (Object element : evaluatorElements) {
            forms.add(EvaluatorFingerprint.of(serializer.apply(element)));
        }
        return String.join(" | ", forms);
    }

    private static final class FactoryHandler implements InvocationHandler {

        private final Object factory;
        private final Function<Object, String> serializer;

        FactoryHandler(Object factory, Function<Object, String> serializer) {
            this.factory = factory;
            this.serializer = serializer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"createEvaluator".equals(method.getName())) {
                return EvaluatorHook.invoke(factory, method, args);
            }
            String fingerprint;
            try {
                // before the factory parses the elements (a parsed literal is written differently)
                fingerprint = fingerprint((Collection<?>) args[0], serializer);
            } catch (RuntimeException e) {
                fingerprint = null;
            }
            Object evaluator = EvaluatorHook.invoke(factory, method, args);
            return evaluator != null ? proxy(evaluator, new EvaluatorHandler(evaluator, fingerprint)) : evaluator;
        }
    }

    private static final class EvaluatorHandler implements InvocationHandler {

        private final Object evaluator;
        private final String fingerprint;

        EvaluatorHandler(Object evaluator, String fingerprint) {
            this.evaluator = evaluator;
            this.fingerprint = fingerprint;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Listener current = listener;
            if (!"evaluate".equals(method.getName()) || current == null) {
                return EvaluatorHook.invoke(evaluator, method, args);
            }
            Object token = before(current, args);
            Object output = null;
            Throwable error = null;
            try {
                output = EvaluatorHook.invoke(evaluator, method, args);
                return output;
            } catch (Throwable e) {
                error = e;
                throw e;
            } finally {
                after(current, token, output, error);
            }
        }

        private Object before(Listener current, Object[] args) {
            // the recorder must never change what midPoint does
            try {
                return current.before(fingerprint, args);
            } catch (RuntimeException e) {
                System.err.println("Evaluation recorder failed: " + e);
                return null;
            }
        }

        private static void after(Listener current, Object token, Object output, Throwable error) {
            try {
                current.after(token, output, error);
            } catch (RuntimeException e) {
                System.err.println("Evaluation recorder failed: " + e);
            }
        }
    }
}
