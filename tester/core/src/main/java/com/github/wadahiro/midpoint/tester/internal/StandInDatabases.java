package com.github.wadahiro.midpoint.tester.internal;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The databases of the manifest's {@code databases}: another system's database that the deployment's scripts read or
 * write over JDBC (another midPoint's audit tables, an application's), stood in for by an in-memory PostgreSQL of its
 * own, with the tables and rows of its {@code init} SQL files. Each test starts from that content. One server per
 * database and JVM, started on first use.
 */
public final class StandInDatabases {

    /** One stand-in: its server, the snapshot after the init scripts. */
    public static final class Database {
        final String name;
        final PgmemServer server;
        final String snapshot;

        Database(String name, PgmemServer server, String snapshot) {
            this.name = name;
            this.server = server;
            this.snapshot = snapshot;
        }

        public String url() {
            return server.jdbcUrl();
        }

        public String user() {
            return server.user();
        }

        void reset() {
            server.reset(snapshot);
        }

        /** A snapshot of the content now (a checkpoint of the test); {@link #restore}, then {@link #release}. */
        String checkpoint() {
            return server.snapshot();
        }

        void restore(String checkpoint) {
            server.reset(checkpoint);
        }

        void release(String checkpoint) {
            server.release(checkpoint);
        }
    }

    private static final Map<String, Database> STARTED = new LinkedHashMap<>();

    private StandInDatabases() {
    }

    /** The database (started and initialized on first use); the key tells deployments apart. */
    static synchronized Database start(String key, String name, List<Path> init, Path workDir) {
        Database db = STARTED.get(key);
        if (db == null) {
            PgmemServer server = PgmemServer.start(name, workDir);
            SqlScript.runFiles(server.templateJdbcUrl(), server.user(), init);
            server.forkTemplate();
            db = new Database(name, server, server.snapshot());
            STARTED.put(key, db);
        }
        return db;
    }
}
