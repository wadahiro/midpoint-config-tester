package com.github.wadahiro.midpoint.tester.internal;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;

import com.github.wadahiro.midpoint.tester.agent.FileRemap;

/**
 * Installs the JVM-level file redirection of {@code mp.files()}: puts {@link FileRemap} on the bootstrap class path
 * and instruments, with inlined Byte Buddy advice, every constructor of
 * <ul>
 *   <li>{@code java.io.File}: its {@code path} is replaced by the physical path when it is under a mapped directory,
 *       so {@code FileInputStream}, {@code FileOutputStream}, {@code RandomAccessFile}, {@code FileReader},
 *       {@code FileWriter}, {@code PrintWriter} (all of which construct a {@code File} from a name) and every
 *       metadata method of {@code File} use the mapped directory;</li>
 *   <li>{@code sun.nio.fs.UnixPath} (Linux, macOS): its {@code path} bytes likewise, so {@code Path.of},
 *       {@code Paths.get}, {@code File.toPath}, {@code Path.resolve} and everything of {@code java.nio.file.Files}
 *       does.</li>
 * </ul>
 * The agent is attached at run time ({@code ByteBuddyAgent.install()}: an agent already given with
 * {@code -javaagent:byte-buddy-agent.jar}, else self-attachment). {@link #probe()} checks the result on this JVM.
 */
final class FileAgent {

    /** By name: a class literal here would load FileRemap from the class path before it is on the bootstrap one. */
    private static final String REMAP = "com.github.wadahiro.midpoint.tester.agent.FileRemap";
    private static final String TIME_SHIFT = "com.github.wadahiro.midpoint.tester.agent.TimeShift";
    private static final String NETWORK_GUARD = "com.github.wadahiro.midpoint.tester.agent.NetworkGuard";
    private static String failure;
    private static boolean installed;

    private FileAgent() {
    }

    /** Installs the redirection once; null if it works, else why not. */
    static synchronized String install(Path workDir) {
        if (installed) {
            return failure;
        }
        installed = true;
        try {
            Instrumentation instrumentation = ByteBuddyAgent.install();
            // FileRemap from the bootstrap class loader, before anything loads it from the class path
            ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(FileAgent.class.getClassLoader());
            Map<String, byte[]> classes = Map.of(REMAP, locator.locate(REMAP).resolve(),
                    REMAP + "$Table", locator.locate(REMAP + "$Table").resolve(),
                    TIME_SHIFT, locator.locate(TIME_SHIFT).resolve(),
                    TIME_SHIFT + "$FirstCaller", locator.locate(TIME_SHIFT + "$FirstCaller").resolve(),
                    NETWORK_GUARD, locator.locate(NETWORK_GUARD).resolve());
            Path jarDir = Files.createDirectories(workDir.resolve("tester-agent"));
            ClassInjector.UsingInstrumentation.of(jarDir.toFile(), ClassInjector.UsingInstrumentation.Target.BOOTSTRAP,
                    instrumentation).injectRaw(classes);
            Class<?> remap = Class.forName(REMAP, true, null);
            if (remap != FileRemap.class) {
                return failure = "FileRemap was loaded from the class path before the bootstrap class path";
            }
            if (Class.forName(TIME_SHIFT, true, null) != com.github.wadahiro.midpoint.tester.agent.TimeShift.class) {
                System.err.println("Tester: the JVM's clock is not shifted: TimeShift was loaded from the class path"
                        + " before the bootstrap class path");
            } else {
                TimeAgent.install(instrumentation);
            }
            if (Class.forName(NETWORK_GUARD, true, null)
                    != com.github.wadahiro.midpoint.tester.agent.NetworkGuard.class) {
                System.err.println("Tester: the JVM's connections are not guarded: NetworkGuard was loaded from the"
                        + " class path before the bootstrap class path");
            } else {
                String network = NetworkAgent.install(instrumentation);
                if (network != null) {
                    System.err.println("Tester: " + network);
                }
            }
            String taskStop = TaskStop.install(instrumentation);
            if (taskStop != null) {
                System.err.println("Tester: " + taskStop);
            }
            List<Throwable> errors = new ArrayList<>();
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader loader, net.bytebuddy.utility.JavaModule module,
                                boolean loaded, Throwable throwable) {
                            errors.add(throwable);
                        }
                    })
                    .ignore(none())
                    .type(named("java.io.File"))
                    .transform((builder, type, loader, module, domain) ->
                            builder.visit(Advice.to(FileAdvice.class).on(isConstructor())))
                    .type(named("sun.nio.fs.UnixPath"))
                    .transform((builder, type, loader, module, domain) ->
                            builder.visit(Advice.to(UnixPathAdvice.class).on(isConstructor())))
                    .type(named("java.lang.ProcessBuilder"))
                    .transform((builder, type, loader, module, domain) ->
                            builder.visit(Advice.to(ProcessBuilderAdvice.class).on(named("start")
                                    .and(net.bytebuddy.matcher.ElementMatchers.takesArguments(0)))))
                    .installOn(instrumentation);
            if (!errors.isEmpty()) {
                return failure = "Cannot instrument the JDK's file classes: " + errors.get(0);
            }
            return failure = probe(workDir);
        } catch (Throwable e) {
            return failure = "Cannot attach the file redirection agent (" + e + "); give it to the JVM with "
                    + "-javaagent:<byte-buddy-agent jar>";
        }
    }

    /** Whether a mapped directory is seen through {@code File} and {@code Path}; null if so, else what is not. */
    private static String probe(Path workDir) {
        String logical = "/tester-probe-" + Long.toHexString(System.nanoTime());
        String physical = workDir.toAbsolutePath().normalize().resolve("tester-agent").resolve("probe").toString();
        FileRemap.set(new String[] { logical }, new String[] { physical }, null);
        try {
            String file = new File(logical + "/x").getPath();
            String path = Paths.get(logical, "x").toString();
            List<String> wrong = new ArrayList<>();
            if (!file.equals(physical + File.separator + "x")) {
                wrong.add("java.io.File (" + file + ")");
            }
            if (!path.equals(physical + File.separator + "x")) {
                wrong.add("java.nio.file.Path (" + path + "; Unix file systems only)");
            }
            return wrong.isEmpty() ? null : "Files are not redirected through " + String.join(", ", wrong);
        } finally {
            FileRemap.set(new String[0], new String[0], null);
        }
    }

    /** Inlined into every {@code java.io.File} constructor. */
    static final class FileAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.FieldValue(value = "path", readOnly = false) String path) {
            String physical = FileRemap.remap(path);
            if (physical != null) {
                path = physical;
            }
        }
    }

    /**
     * Inlined into {@code ProcessBuilder.start()}: the mapped directories in the command's arguments become the
     * physical ones (a shell command of a report's post-report script, {@code "...".execute()} in Groovy), as the
     * process does not go through {@code File} or {@code Path}.
     */
    static final class ProcessBuilderAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.FieldValue(value = "command", readOnly = false) java.util.List<String> command) {
            java.util.List<String> physical = FileRemap.remapCommand(command);
            if (physical != null) {
                command = physical;
            }
        }
    }

    /** Inlined into every {@code sun.nio.fs.UnixPath} constructor. */
    static final class UnixPathAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.FieldValue(value = "path", readOnly = false) byte[] path,
                @Advice.FieldValue(value = "stringValue", readOnly = false) String stringValue) {
            byte[] physical = FileRemap.remap(path);
            if (physical != null) {
                path = physical;
                stringValue = null;
            }
        }
    }
}
