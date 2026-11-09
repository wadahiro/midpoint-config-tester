package com.github.wadahiro.midpoint.configtester;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.internal.StandInDatabases;

/**
 * A stand-in database of the manifest ({@link MidPointConfigTester#database}): another system's database the deployment's scripts
 * read or write over JDBC, an in-memory PostgreSQL with the tables of its {@code init} files, at that content at the
 * start of each test. A test puts the rows the scripts read and checks what they wrote. The stand-in is part of the
 * simulated world: a time the test writes for the other system is on the test's clock ({@code t.clock().now()}, an
 * {@code Instant} parameter), which the scripts read too; the stand-in's own {@code now()} is the real time.
 *
 * <pre>{@code
 * t.database("audit").execute("INSERT INTO m_audit_event (targetOid, eventType) VALUES (?, ?)", oid, 3);
 * assertThat(t.database("audit").rows("SELECT * FROM m_user")).isEmpty();
 * }</pre>
 */
public final class Database {

    private final String url;
    private final String user;

    Database(StandInDatabases.Database db) {
        this(db.url(), db.user());
    }

    Database(String url, String user) {
        this.url = url;
        this.user = user;
    }

    /** The JDBC URL the scripts get ({@code ${database.<name>.url}} in the manifest's {@code environment}). */
    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    /** Runs a statement with parameters ({@code ?}); returns the update count. */
    public int execute(String sql, Object... parameters) {
        try (Connection c = connect(); PreparedStatement st = prepare(c, sql, parameters)) {
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run " + sql + ": " + e.getMessage(), e);
        }
    }

    /** The rows of a query with parameters, each a map of column (lower case) to value, in order. */
    public List<Map<String, Object>> rows(String sql, Object... parameters) {
        try (Connection c = connect(); PreparedStatement st = prepare(c, sql, parameters);
                ResultSet rs = st.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run " + sql + ": " + e.getMessage(), e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, "");
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... parameters) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            Object p = parameters[i];
            // an Instant (t.clock().now(): the time the scripts read) as a timestamp, which every JDBC driver takes
            st.setObject(i + 1, p instanceof java.time.Instant ? java.sql.Timestamp.from((java.time.Instant) p) : p);
        }
        return st;
    }
}
