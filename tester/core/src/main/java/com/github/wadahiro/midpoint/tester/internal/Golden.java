package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.github.wadahiro.midpoint.tester.Run;

/**
 * Golden results of the test classes (see {@link GoldenResults} for what is recorded): record them on one midPoint
 * line, verify them on another to see what an upgrade changes.
 *
 * System properties:
 * <ul>
 *   <li>{@code golden.mode}: {@code off} (default), {@code record} or {@code verify};</li>
 *   <li>{@code golden.dir}: the golden files, {@code <test class>.golden}, {@code src/test/golden} by default;
 *       recording replaces the tests that ran and keeps the others;</li>
 *   <li>{@code golden.failOn}: the kinds of differences that fail a test ({@link GoldenComparison.Kind}), by default
 *       {@code changed,missing,new}; the others ({@code old}, {@code text}, {@code count}, {@code inputs},
 *       {@code run}, a test without golden results) are notes in the report; {@code none} reports only;</li>
 *   <li>{@code golden.outcomes}: {@code final} (default) compares the final outcomes of each place, {@code all}
 *       every outcome, intermediate projector waves included;</li>
 *   <li>{@code golden.baseline}: {@code note} (default) reports differences at places of midPoint's own baseline
 *       objects as notes, {@code compare} lets them fail;</li>
 *   <li>{@code golden.accepted}: a file of accepted differences ({@link GoldenComparison#parseAccepted}), reported
 *       as notes, and of options of the comparison ({@link GoldenComparison#parseOptions}).</li>
 * </ul>
 * Differences of a test whose golden results were recorded while it failed are notes, and so are those of a test
 * that fails now (its own failure is what the report should show).
 * In both modes the results of the run are written to {@code <workDir>/tester/golden/<test class>.golden}; verifying
 * writes {@code <test class>.diff.md} there when something differs (and removes an older one otherwise). Verifying a
 * class without a golden file fails the class (after its tests); a test missing in an existing file is a note.
 */
public final class Golden {

    public enum Mode { OFF, RECORD, VERIFY }

    /** The results of one test class while its tests run. */
    public static final class ClassResults {
        final String className;
        final GoldenResults actual = new GoldenResults(LineSupport.LINE);
        GoldenResults golden;
        /** Verifying without a golden file for the class (a wrong {@code golden.dir}?): fails the class. */
        Path missingFile;
        final StringBuilder report = new StringBuilder();
        int failingTests;

        ClassResults(String className) {
            this.className = className;
        }
    }

    private Golden() {
    }

    public static Mode mode() {
        return Mode.valueOf(System.getProperty("golden.mode", "off").strip().toUpperCase(Locale.ROOT));
    }

    static Path dir() {
        return Path.of(System.getProperty("golden.dir", "src/test/golden"));
    }

    static GoldenComparison.Options options() {
        GoldenComparison.Options options = GoldenComparison.Options.defaults();
        options.allOutcomes = "all".equalsIgnoreCase(System.getProperty("golden.outcomes", "final").strip());
        options.compareBaseline = "compare".equalsIgnoreCase(System.getProperty("golden.baseline", "note").strip());
        GoldenComparison.apply(fileOptions(), options);
        return options;
    }

    private static List<GoldenComparison.Acceptance> accepted;
    private static List<GoldenComparison.Option> fileOptions;

    static synchronized List<GoldenComparison.Option> fileOptions() {
        if (fileOptions == null) {
            String file = acceptedFile();
            fileOptions = file.isEmpty() ? List.of() : GoldenComparison.parseOptions(read(Path.of(file)));
        }
        return fileOptions;
    }

    private static String acceptedFile() {
        return System.getProperty("golden.accepted", "").strip();
    }

    static synchronized List<GoldenComparison.Acceptance> accepted() {
        if (accepted == null) {
            String file = acceptedFile();
            accepted = file.isEmpty() ? List.of() : GoldenComparison.parseAccepted(read(Path.of(file)));
        }
        return accepted;
    }

    static Set<GoldenComparison.Kind> failing() {
        String failOn = System.getProperty("golden.failOn");
        return failOn != null ? GoldenComparison.parseKinds(failOn) : GoldenComparison.DEFAULT_FAILING;
    }

    /** Null when golden results are off. */
    public static ClassResults start(Class<?> testClass) {
        if (mode() == Mode.OFF) {
            return null;
        }
        ClassResults results = new ClassResults(testClass.getName());
        if (mode() == Mode.VERIFY) {
            Path file = dir().resolve(results.className + ".golden");
            if (Files.exists(file)) {
                results.golden = GoldenResults.parse(read(file));
            } else {
                results.missingFile = file.toAbsolutePath();
                results.golden = new GoldenResults("?");
            }
        }
        return results;
    }

    /**
     * Takes the results of a test (or of {@link GoldenResults#BEFORE_ALL}); when verifying, compares them and returns
     * the failure message, or null when nothing failing differs.
     */
    public static String testFinished(ClassResults results, String test, List<Run> runs) {
        return testFinished(results, test, runs, null);
    }

    /** As {@link #testFinished(ClassResults, String, List)}, for a test that failed ({@code failure} non-null). */
    public static String testFinished(ClassResults results, String test, List<Run> runs, Throwable failure) {
        if (results == null) {
            return null;
        }
        GoldenResults.TestResult actual = GoldenResults.of(test, runs, failure);
        results.actual.put(actual);
        if (results.golden == null) {
            return null;
        }
        GoldenResults.TestResult golden = results.golden.tests().get(test);
        List<GoldenComparison.Difference> differences = GoldenComparison.compare(golden, actual, options());
        if (differences.isEmpty()) {
            return null;
        }
        String testName = simpleName(results.className) + "." + test;
        GoldenComparison.accept(testName, differences, accepted());
        String remark = null;
        if (golden != null && golden.failure != null) {
            remark = "Recorded while the test failed (" + golden.failure + "): differences are notes.";
        } else if (failure != null) {
            remark = "The test failed now: differences are notes.";
        }
        if (remark != null) {
            differences.forEach(d -> d.note = d.note != null ? d.note : "test failed");
        }
        Set<GoldenComparison.Kind> failing = failing();
        String section = GoldenComparison.markdown(testName, differences, failing, remark);
        results.report.append('\n').append(section);
        if (!GoldenComparison.fails(differences, failing)) {
            return null;
        }
        results.failingTests++;
        return "Evaluations differ from the golden results (recorded with midPoint " + results.golden.line()
                + ", now " + LineSupport.LINE + "):\n" + section;
    }

    /**
     * Writes the results of the class (and the golden file when recording, the report when verifying); returns the
     * failure message when verifying found no golden file for the class, else null.
     */
    public static String classFinished(ClassResults results, Path workDir) {
        if (results == null) {
            return null;
        }
        String title = results.className;
        Path out = workDir.resolve("tester/golden");
        write(out.resolve(title + ".golden"), results.actual.write(title));
        if (mode() == Mode.RECORD) {
            Path file = dir().resolve(title + ".golden");
            GoldenResults merged = Files.exists(file) ? GoldenResults.parse(read(file)) : results.actual;
            if (merged != results.actual) {
                merged.merge(results.actual);
            }
            write(file, merged.write(title));
        } else if (mode() == Mode.VERIFY) {
            Path report = out.resolve(title + ".diff.md");
            if (results.report.length() == 0) {
                try {
                    Files.deleteIfExists(report);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                write(report, "## " + title + "\n\nGolden results recorded with midPoint " + results.golden.line()
                        + ", verified with " + LineSupport.LINE + ": " + results.failingTests
                        + " failing tests. Failing kinds: " + failing() + "; the others are notes.\n"
                        + results.report);
            }
        }
        return results.missingFile == null ? null
                : "No golden results for " + title + ": " + results.missingFile + " does not exist (record them with "
                        + "-Dgolden.mode=record, or check -Dgolden.dir)";
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read golden results " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
