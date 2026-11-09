package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a report wrote ({@link Report#run()}): its lines and, for a CSV export, its rows by column header. The CSV is
 * midPoint's default format ({@code ;} between cells, {@code "} around them, a header row); the note 4.8+ writes at
 * the end of an export without a subscription is not a row, nor is the byte order mark 4.8.14 writes before the
 * header part of the first column's name. An HTML export has lines and text only.
 */
public final class ReportOutput {

    /** What midPoint 4.8+ writes after the rows when it has no subscription. */
    private static final String SUBSCRIPTION_NOTE = "No active subscription.";

    private final String report;
    private final List<String> lines;

    ReportOutput(String report, List<String> lines) {
        this.report = report;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    /** The lines of the file as midPoint wrote it. */
    public List<String> lines() {
        return lines;
    }

    public String text() {
        return String.join("\n", lines);
    }

    /** Whether midPoint wrote HTML (a report whose file format is HTML). */
    public boolean isHtml() {
        return !lines.isEmpty() && firstLine().trim().startsWith("<");
    }

    /** The column headers of a CSV export. */
    public List<String> columns() {
        csvOnly();
        return lines.isEmpty() ? List.of() : cells(firstLine());
    }

    /** The first line without the byte order mark (4.8.14 starts a CSV export with one). */
    private String firstLine() {
        String line = lines.get(0);
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    /** The rows of a CSV export, each by column header, in the order written. */
    public List<Map<String, String>> rows() {
        List<String> columns = columns();
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            if (line.isEmpty() || line.startsWith(SUBSCRIPTION_NOTE)) {
                continue;
            }
            List<String> cells = cells(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), i < cells.size() ? cells.get(i) : null);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The values of one column, row by row. */
    public List<String> column(String header) {
        if (!columns().contains(header)) {
            throw new AssertionError("Report " + report + " has no column " + header + "; columns: " + columns());
        }
        List<String> out = new ArrayList<>();
        rows().forEach(row -> out.add(row.get(header)));
        return out;
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    public ReportOutput hasRows(int expected) {
        if (rows().size() != expected) {
            throw new AssertionError("Report " + report + ": expected " + expected + " row(s), found " + rows().size()
                    + ":\n" + text());
        }
        return this;
    }

    /** A row has this value in the column. */
    public ReportOutput hasRowWith(String header, String value) {
        if (!column(header).contains(value)) {
            throw new AssertionError("Report " + report + ": no row with " + header + " = " + value + ":\n" + text());
        }
        return this;
    }

    public ReportOutput hasNoRowWith(String header, String value) {
        if (column(header).contains(value)) {
            throw new AssertionError("Report " + report + ": a row with " + header + " = " + value + ":\n" + text());
        }
        return this;
    }

    public ReportOutput contains(String text) {
        if (!text().contains(text)) {
            throw new AssertionError("Report " + report + ": expected the output to contain '" + text + "':\n"
                    + text());
        }
        return this;
    }

    @Override
    public String toString() {
        return text();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void csvOnly() {
        if (isHtml()) {
            throw new IllegalStateException("Report " + report + " is HTML: read lines() or text()");
        }
    }

    /** The cells of a line: {@code ;}-separated, each optionally in {@code "}, {@code ""} an escaped quote. */
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ';') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
