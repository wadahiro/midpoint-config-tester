package com.github.wadahiro.midpoint.tester.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.wadahiro.midpoint.tester.check.Finding.Check;
import com.github.wadahiro.midpoint.tester.check.Finding.Severity;
import com.github.wadahiro.midpoint.tester.internal.ConfigFiles;

/** The outcome of {@link StaticCheck#run()}: findings by check and severity, a Markdown report, assertions. */
public final class StaticReport {

    private final Path dir;
    private final String line;
    private final Set<Check> checks;
    private final Map<Check, String> skipped;
    private final int files;
    private final int objects;
    private final List<ConfigFiles.Place> places;
    private final List<Finding> findings;

    StaticReport(Path dir, String line, Set<Check> checks, Map<Check, String> skipped, int files, int objects,
            List<ConfigFiles.Place> places, List<Finding> findings) {
        this.dir = dir;
        this.line = line;
        this.checks = Set.copyOf(checks);
        this.skipped = Map.copyOf(skipped);
        this.files = files;
        this.objects = objects;
        this.places = List.copyOf(places);
        this.findings = List.copyOf(findings);
    }

    public Path dir() {
        return dir;
    }

    /** The midPoint line checked against, e.g. {@code 4.8}. */
    public String line() {
        return line;
    }

    /** Why the check did not run on this line ({@link Check#UPGRADE} before 4.4); null if it ran or was not asked. */
    public String skipped(Check check) {
        return skipped.get(check);
    }

    /** The expression places of the files, with their identities. */
    public List<ConfigFiles.Place> places() {
        return places;
    }

    public List<Finding> findings() {
        return findings;
    }

    public List<Finding> findings(Check check) {
        return findings.stream().filter(f -> f.check() == check).collect(Collectors.toList());
    }

    public List<Finding> findings(Check check, Severity severity) {
        return findings.stream().filter(f -> f.check() == check && f.severity() == severity)
                .collect(Collectors.toList());
    }

    /** Every finding of severity {@link Severity#ERROR}. */
    public List<Finding> errors() {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR).collect(Collectors.toList());
    }

    public List<Finding> errors(Check check) {
        return findings(check, Severity.ERROR);
    }

    /** Scripts that do not compile on this line. */
    public List<Finding> compileErrors() {
        return errors(Check.COMPILE);
    }

    /** Fails with an {@link AssertionError} listing the errors of these checks (all checks when none given). */
    public void assertNoErrors(Check... only) {
        List<Check> selected = only.length == 0 ? Arrays.asList(Check.values()) : Arrays.asList(only);
        List<Finding> errors = errors().stream().filter(f -> selected.contains(f.check()))
                .collect(Collectors.toList());
        if (!errors.isEmpty()) {
            throw new AssertionError(errors.size() + " static check error" + (errors.size() > 1 ? "s" : "") + " in "
                    + dir + " (midPoint " + line + "):\n"
                    + errors.stream().map(f -> "  " + f).collect(Collectors.joining("\n")));
        }
    }

    /** Writes {@link #markdown()} to the file (directories created). */
    public StaticReport write(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, markdown(), StandardCharsets.UTF_8);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** The report: a summary per check, then the findings of each check, keyed by identity. */
    public String markdown() {
        StringBuilder md = new StringBuilder("# Static check of ").append(dir.getFileName()).append(" (midPoint ")
                .append(line).append(")\n\n");
        long groovy = places.stream().filter(p -> p.entry.hasGroovy()).count();
        md.append(files).append(" files, ").append(objects).append(" objects, ").append(places.size())
                .append(" expression places (").append(groovy).append(" with Groovy): ")
                .append(count(null, Severity.ERROR)).append(" errors, ").append(count(null, Severity.WARNING))
                .append(" warnings, ").append(count(null, Severity.NOTE)).append(" notes.\n\n");
        md.append("Findings are identified structurally (owner object and element path, as the tester identifies "
                + "evaluations); file and line are for display.\n\n");
        md.append("| Check | Errors | Warnings | Notes |\n|---|---:|---:|---:|\n");
        for (Check check : Check.values()) {
            md.append("| ").append(check.title).append(" | ");
            if (skipped.containsKey(check)) {
                md.append("skipped | | |\n");
            } else if (checks.contains(check)) {
                md.append(count(check, Severity.ERROR)).append(" | ").append(count(check, Severity.WARNING))
                        .append(" | ").append(count(check, Severity.NOTE)).append(" |\n");
            } else {
                md.append("not run | | |\n");
            }
        }
        for (Check check : Check.values()) {
            if (!checks.contains(check)) {
                continue;
            }
            md.append("\n## ").append(check.title).append("\n\n");
            if (skipped.containsKey(check)) {
                md.append("Skipped: ").append(skipped.get(check)).append(".\n");
                continue;
            }
            List<Finding> list = findings(check);
            if (list.isEmpty()) {
                md.append("None.\n");
                continue;
            }
            md.append("| Severity | Rule | Identity | File | Message |\n|---|---|---|---|---|\n");
            for (Severity severity : Severity.values()) {
                for (Finding f : findings(check, severity)) {
                    md.append("| ").append(severity).append(" | ").append(f.rule()).append(" | ")
                            .append(cell(f.where())).append(" | ").append(cell(f.position())).append(" | ")
                            .append(cell(f.message())).append(" |\n");
                }
            }
        }
        return md.toString();
    }

    private long count(Check check, Severity severity) {
        return findings.stream().filter(f -> (check == null || f.check() == check) && f.severity() == severity)
                .count();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    @Override
    public String toString() {
        return "StaticReport[" + dir + ", midPoint " + line + ", " + findings.size() + " findings]";
    }
}
