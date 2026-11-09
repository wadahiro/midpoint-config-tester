package com.github.wadahiro.midpoint.tester.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/**
 * The data of midPoint's database to files and back, for the deployment cache ({@link DeploymentCache}): pgmem keeps
 * its snapshots in memory only (its control protocol has snapshot, reset, fork and close, nothing that writes to
 * disk), so the tables are copied out with PostgreSQL's binary {@code COPY} over JDBC, in one repeatable-read
 * transaction, and copied back into a database of the same schema (the tester creates it at every start) after
 * truncating every table, with triggers and foreign keys off ({@code session_replication_role = replica}) as for a
 * restore of a dump. Sequences get their values back. The schema's fingerprint (tables and columns) is kept: a
 * database of another schema is not restored into.
 */
final class PgDump {

    private PgDump() {
    }

    /** Writes the data of every table to {@code dir} (created): {@code tables.txt}, one file per table. */
    static void dump(String jdbcUrl, String user, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, "")) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
            List<String> tables = tables(c);
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < tables.size(); i++) {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dir.resolve(i + ".bin")))) {
                    copy.copyOut("COPY " + tables.get(i) + " TO STDOUT (FORMAT binary)", out);
                }
                list.append(tables.get(i)).append('\n');
            }
            Files.writeString(dir.resolve("tables.txt"), list.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("sequences.txt"), sequences(c), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("schema.txt"), fingerprint(c), StandardCharsets.UTF_8);
            c.rollback();
        } catch (SQLException e) {
            throw new IOException("Cannot copy the database out: " + e.getMessage(), e);
        }
    }

    /** Replaces the data of the database by the dump in {@code dir}; fails if the schema is not the dump's. */
    static void restore(String jdbcUrl, String user, Path dir) throws IOException {
        List<String> tables = Files.readAllLines(dir.resolve("tables.txt"), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, "")) {
            c.setAutoCommit(false);
            String schema = Files.readString(dir.resolve("schema.txt"), StandardCharsets.UTF_8);
            if (!schema.equals(fingerprint(c))) {
                throw new IOException("The database schema is not the one the dump was taken from");
            }
            CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
                s.execute("SET LOCAL lock_timeout = '10s'");
                // CASCADE: a foreign key of a partitioned table (not in the list) refers to a truncated one
                s.execute("TRUNCATE " + String.join(", ", tables(c)) + " CASCADE");
            }
            for (int i = 0; i < tables.size(); i++) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(dir.resolve(i + ".bin")))) {
                    copy.copyIn("COPY " + tables.get(i) + " FROM STDIN (FORMAT binary)", in);
                }
            }
            try (Statement s = c.createStatement()) {
                for (String line : Files.readAllLines(dir.resolve("sequences.txt"), StandardCharsets.UTF_8)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if ("-".equals(parts[1])) {
                        s.execute("ALTER SEQUENCE " + parts[0] + " RESTART");
                    } else {
                        s.execute("SELECT setval('" + parts[0].replace("'", "''") + "', " + Long.parseLong(parts[1])
                                + ", true)");
                    }
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IOException("Cannot copy the database in: " + e.getMessage(), e);
        }
    }

    /** The tables (with data: not the partitioned parents) as quoted {@code schema.table}, in name order. */
    private static List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT quote_ident(n.nspname) || '.' || quote_ident(c.relname)"
                        + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema')"
                        + " AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** {@code name<TAB>last value} ({@code -}: never used) per sequence. */
    private static String sequences(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT quote_ident(schemaname) || '.' || quote_ident(sequencename),"
                        + " last_value FROM pg_sequences ORDER BY 1")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long value = rs.getLong(2);
                boolean unused = rs.wasNull();
                sb.append(name).append('\t').append(unused ? "-" : String.valueOf(value)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Tables and their columns with types, one per line. */
    private static String fingerprint(Connection c) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT table_schema, table_name, column_name, data_type"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
                        + " ORDER BY table_schema, table_name, ordinal_position")) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('.').append(rs.getString(2)).append(' ').append(rs.getString(3))
                        .append(' ').append(rs.getString(4)).append('\n');
            }
        }
        return sb.toString();
    }
}
