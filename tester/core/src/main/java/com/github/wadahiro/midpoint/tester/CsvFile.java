package com.github.wadahiro.midpoint.tester;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The file the real CSV connector of a resource reads and writes ({@link MidPoint#csv(String)}, for a resource imported
 * with {@link ResourceOptions#realConnector()}): a file under the working directory instead of the deployed
 * {@code filePath}. Rows are written and read with the resource's {@code fieldDelimiter}, {@code quote},
 * {@code escape}, {@code commentMarker}, {@code recordSeparator}, {@code multivalueDelimiter} and {@code encoding}
 * (the connector's defaults: {@code ;}, {@code "}, {@code \}, {@code #}, CRLF, none, UTF-8); a value is quoted only
 * when it has to be (delimiter, quote, escape, line break). Changes are seen by
 * midPoint when it next reads the resource (an import, a reconciliation).
 *
 * The file starts with a header: the unique column, the name column, the password column (as configured), then the
 * {@code ri:} attributes of the account object types of {@code schemaHandling}, in order. {@link #writeText(String)}
 * replaces everything, header included.
 */
public interface CsvFile {

    Path path();

    /** The columns of the first line. */
    List<String> header();

    /** The rows below the header, as column to value (an empty field is an empty string). */
    List<Map<String, String>> rows();

    /** Replaces the rows, keeping the header; a missing column is empty, an unknown column fails. */
    void write(List<? extends Map<String, ?>> rows);

    /** Appends a row. */
    void addRow(Map<String, ?> row);

    /** The whole file as text. */
    String text();

    /** Replaces the whole file, header included (e.g. a sample export of the real source system). */
    void writeText(String text);
}
