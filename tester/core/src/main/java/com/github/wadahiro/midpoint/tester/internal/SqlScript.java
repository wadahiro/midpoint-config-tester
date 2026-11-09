package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a psql-style script statement by statement. midPoint's schema scripts call procedures that COMMIT, which
 * PostgreSQL rejects inside the implicit transaction of a multi-statement query.
 */
final class SqlScript {

    private SqlScript() {
    }

    static void run(String jdbcUrl, String user, String... classpathResources) {
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, "")) {
            for (String resource : classpathResources) {
                String sql;
                try (InputStream in = SqlScript.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("SQL script not found: " + resource);
                    }
                    sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                for (String statement : split(sql)) {
                    try (Statement st = c.createStatement()) {
                        st.execute(statement);
                    } catch (SQLException e) {
                        throw new IllegalStateException("Failed in " + resource + ": " + e.getMessage() + "\n" + statement, e);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Cannot load the midPoint schema", e);
        }
    }

    /** Splits on {@code ;} outside quotes ('', "", $tag$) and comments. */
    static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e;
                continue;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            } else if (c == '\'' || c == '"') {
                int e = i + 1;
                while (e < n && sql.charAt(e) != c) {
                    e++;
                }
                cur.append(sql, i, Math.min(e + 1, n));
                i = e + 1;
                continue;
            } else if (c == '$') {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(sql.charAt(e)) || sql.charAt(e) == '_')) {
                    e++;
                }
                if (e < n && sql.charAt(e) == '$') {
                    String tag = sql.substring(i, e + 1);
                    int end = sql.indexOf(tag, e + 1);
                    end = end < 0 ? n : end + tag.length();
                    cur.append(sql, i, end);
                    i = end;
                    continue;
                }
            } else if (c == ';') {
                add(out, cur);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String statement = cur.toString().strip();
        if (!statement.isEmpty()) {
            out.add(statement);
        }
        cur.setLength(0);
    }
}
