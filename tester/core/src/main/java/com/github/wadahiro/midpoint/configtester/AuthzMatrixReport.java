package com.github.wadahiro.midpoint.configtester;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The HTML report of an authorization matrix ({@link AuthzMatrixTest}): one self-contained file (no scripts or
 * fonts from elsewhere), the template {@code tester/authz-matrix.html} with the matrix as JSON.
 */
final class AuthzMatrixReport {

    private AuthzMatrixReport() {
    }

    static Path write(AuthzMatrixSpec spec, String line, List<AuthzMatrixTest.Cell> cells,
            Map<String, String> authorizations, List<String> warnings, Path dir) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"warnings\":").append(strings(warnings)).append(',');
        field(json, "file", spec.file).append(',');
        field(json, "line", line).append(',');
        field(json, "generated", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append(',');
        json.append("\"personas\":[");
        boolean first = true;
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            json.append(first ? "" : ",").append('{');
            first = false;
            field(json, "id", persona.id).append(',');
            field(json, "label", persona.label).append(',');
            field(json, "group", persona.group).append(',');
            field(json, "user", persona.user).append(',');
            field(json, "archetype", persona.archetype).append(',');
            json.append("\"roles\":").append(strings(persona.roles)).append(',');
            json.append("\"orgs\":").append(strings(persona.orgs.stream().map(Object::toString)
                    .collect(java.util.stream.Collectors.toList()))).append(',');
            field(json, "authorizations", authorizations.get(persona.id));
            json.append('}');
        }
        json.append("],\"rows\":[");
        first = true;
        for (AuthzMatrixSpec.Row row : spec.rows) {
            json.append(first ? "" : ",");
            first = false;
            row(json, row, spec);
        }
        json.append("],\"cells\":{");
        first = true;
        for (AuthzMatrixTest.Cell cell : cells) {
            json.append(first ? "" : ",");
            first = false;
            string(json, cell.persona.id + "|" + cell.row.path).append(":{");
            field(json, "e", cell.expected).append(',');
            field(json, "a", cell.actual).append(',');
            field(json, "s", cell.status()).append(',');
            field(json, "rule", cell.rule == null ? null : cell.rule.name).append(',');
            json.append("\"ruleLine\":").append(cell.rule == null ? "null" : String.valueOf(cell.rule.line))
                    .append(',');
            field(json, "ruleNote", cell.rule == null ? null : cell.rule.note).append(',');
            field(json, "detail", cell.detail);
            json.append('}');
        }
        json.append("}}");

        String template;
        try (InputStream in = AuthzMatrixReport.class.getClassLoader().getResourceAsStream("tester/authz-matrix.html")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = spec.file.replaceAll("\\.ya?ml$", "");
        // the JSON in a script element: no "</" may close it
        String html = template.replace("/*MATRIX*/null", json.toString().replace("</", "<\\/"))
                .replace("<!--TITLE-->", escape(name + " · midPoint " + line));
        Path file = dir.resolve(name + "-" + line + ".html");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static void row(StringBuilder json, AuthzMatrixSpec.Row row, AuthzMatrixSpec spec) {
        json.append('{');
        field(json, "path", row.path).append(',');
        field(json, "label", row.label).append(',');
        field(json, "check", row.check).append(',');
        field(json, "object", row.object).append(',');
        json.append("\"line\":").append(row.line).append(',');
        json.append("\"critical\":").append(!row.isGroup() && spec.critical(row));
        if (row.isGroup()) {
            json.append(",\"rows\":[");
            boolean first = true;
            for (AuthzMatrixSpec.Row child : row.children) {
                json.append(first ? "" : ",");
                first = false;
                row(json, child, spec);
            }
            json.append(']');
        }
        json.append('}');
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        string(json, name).append(':');
        return value == null ? json.append("null") : string(json, value);
    }

    private static String strings(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            string(json.append(i == 0 ? "" : ","), values.get(i));
        }
        return json.append(']').toString();
    }

    private static StringBuilder string(StringBuilder json, String value) {
        json.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
            }
        }
        return json.append('"');
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
