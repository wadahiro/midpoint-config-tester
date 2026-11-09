package com.github.wadahiro.midpoint.tester.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory PostgreSQL (<a href="https://github.com/shibukawa/pgmem">pgmem</a>) driven directly through its
 * JSON-lines control protocol on stdin/stdout.
 *
 * The pgmem Java client needs Java 17, midPoint 4.0 tests run on Java 11, so this small client is used on every line.
 * Only the {@code pgmem-native} artifact (the binary) is needed on the classpath.
 *
 * midPoint talks to a fork of the template server: the template only receives the schema, the fork can be
 * snapshotted and reset (its JDBC URL and pooled connections stay valid across a reset).
 */
public final class PgmemServer implements AutoCloseable {

    private static final int PROTOCOL = 1;
    private static final Pattern PORT = Pattern.compile("\"port\":(\\d+)");

    private final Process process;
    private final BufferedReader out;
    private final Writer in;
    private final int templatePort;
    private int seq;
    private String forkId;
    private int forkPort;

    private PgmemServer(Process process) throws IOException {
        this.process = process;
        this.out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        String ready = out.readLine();
        if (ready == null || !ready.contains("\"event\":\"ready\"")) {
            throw new IllegalStateException("pgmem did not start: " + ready);
        }
        if (!ready.contains("\"protocol\":" + PROTOCOL)) {
            throw new IllegalStateException("unsupported pgmem control protocol: " + ready);
        }
        templatePort = port(ready);
    }

    /** Starts pgmem with one database; the binary comes from the {@code pgmem-native} jar (or {@code pgmem.binary}). */
    public static PgmemServer start(String database, Path workDir) {
        try {
            Path binary = binary(workDir);
            Process process = new ProcessBuilder(binary.toString(), "-database", database, "-user", "postgres")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            PgmemServer server = new PgmemServer(process);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // midPoint shuts down in Spring's hook (task manager, repository) and needs the database until then:
                // stopped first, a pooled connection checked again at shutdown waits for the pool's timeout (30 s)
                awaitThread(SPRING_SHUTDOWN_HOOK, SHUTDOWN_WAIT_MILLIS);
                server.close();
            }));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start pgmem", e);
        }
    }

    private static final String SPRING_SHUTDOWN_HOOK = "SpringContextShutdownHook";
    private static final long SHUTDOWN_WAIT_MILLIS = 20_000;

    /**
     * Waits for the thread of this name to end (at most {@code millis}). Shutdown hooks start in no fixed order: the
     * thread is looked for during the first second.
     */
    private static void awaitThread(String name, long millis) {
        long until = System.nanoTime() + 1_000_000_000L;
        try {
            while (System.nanoTime() - until < 0) {
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (name.equals(thread.getName()) && thread != Thread.currentThread()) {
                        thread.join(millis);
                        return;
                    }
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String templateJdbcUrl() {
        return jdbcUrl(templatePort);
    }

    /** Creates the fork midPoint will use, from the current state of the template. */
    public synchronized void forkTemplate() {
        String snapshot = field(request("snapshot", "\"server\":\"template\""), "snapshot");
        String response = request("fork", "\"snapshot\":\"" + snapshot + "\"");
        forkId = field(response, "id");
        forkPort = port(response);
    }

    public String jdbcUrl() {
        return jdbcUrl(forkPort);
    }

    public String user() {
        return "postgres";
    }

    /** Snapshot of the fork; returns its id. Waits for open transactions. */
    public String snapshot() {
        return field(request("snapshot", "\"server\":\"" + forkId + "\",\"timeout_ms\":5000"), "snapshot");
    }

    /** Restores the fork's data to the snapshot. Fails (code busy) if a transaction stays open. */
    public void reset(String snapshotId) {
        request("reset", "\"server\":\"" + forkId + "\",\"snapshot\":\"" + snapshotId + "\",\"timeout_ms\":5000");
    }

    /** Frees a snapshot that is no longer needed. */
    public void release(String snapshotId) {
        request("close", "\"snapshot\":\"" + snapshotId + "\"");
    }

    @Override
    public void close() {
        // pgmem stops (with all forks) when its stdin is closed
        try {
            in.close();
        } catch (IOException ignored) {
            // already gone
        }
        process.destroy();
    }

    private synchronized String request(String op, String fields) {
        try {
            int id = ++seq;
            in.write("{\"id\":" + id + ",\"op\":\"" + op + "\"," + fields + "}\n");
            in.flush();
            String idField = "\"id\":" + id;
            String line;
            while ((line = out.readLine()) != null) {
                // responses carry the numeric request id; events (no id) are skipped
                if (line.contains(idField + ",") || line.contains(idField + "}")) {
                    if (!line.contains("\"ok\":true")) {
                        throw new IllegalStateException("pgmem " + op + " failed: " + line);
                    }
                    return line;
                }
            }
            throw new IllegalStateException("pgmem exited during " + op);
        } catch (IOException e) {
            throw new IllegalStateException("pgmem " + op + " failed", e);
        }
    }

    private static String jdbcUrl(int port) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/midpoint?sslmode=disable";
    }

    private static int port(String json) {
        Matcher m = PORT.matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no port in pgmem response: " + json);
        }
        return Integer.parseInt(m.group(1));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no " + name + " in pgmem response: " + json);
        }
        return m.group(1);
    }

    private static Path binary(Path workDir) throws IOException {
        String explicit = System.getProperty("pgmem.binary", System.getenv("PGMEM_BINARY"));
        if (explicit != null && !explicit.isEmpty()) {
            return Path.of(explicit);
        }
        String name = isWindows() ? "pgmem.exe" : "pgmem";
        String resource = "/io/github/shibukawa/pgmem/native/" + classifier() + "/" + name;
        Path target = workDir.resolve("pgmem-bin").resolve(name);
        try (InputStream bin = PgmemServer.class.getResourceAsStream(resource)) {
            if (bin == null) {
                throw new IllegalStateException("pgmem binary " + resource + " not on the classpath: add"
                        + " io.github.shibukawa.pgmem:pgmem-native with classifier " + classifier());
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), name, ".part");
            Files.copy(bin, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().setExecutable(true);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String classifier() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osName = os.contains("mac") ? "darwin" : os.contains("win") ? "windows" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return osName + "-" + (arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x86_64");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
