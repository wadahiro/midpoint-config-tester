package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loaded deployments kept on disk across JVMs: after a fresh load the database (a {@link PgDump}), the content of
 * the target systems (mirror objects, CSV files, LDAP entries with their change log), the mapped files and what the
 * load reported are written under {@code <cache dir>/<deployment>/<key>/}; the next JVM with the same key restores
 * them into its midPoint instead of importing (see {@link Deployment}).
 *
 * <p>The key is a SHA-256 of: the line, the jars of the class path (midPoint, connectors), the tester's own classes, the manifest, every file of the
 * deployment on this line (config dirs, overlay; the properties are in the manifest), the initial data, the extension schema.
 * An entry is written to a temporary directory and renamed, so it is complete or absent; every file's digest is
 * checked before a restore. Builders in several JVMs (test forks) take a file lock: the first builds, the others wait
 * and restore. Only the last entry of a deployment is kept.</p>
 *
 * <p>System properties: {@code tester.cache} ({@code true} by default; {@code false} loads afresh every time),
 * {@code tester.cache.dir} (default {@code <tester.workDir>/tester-cache}, shared by the forks).</p>
 */
final class DeploymentCache {

    /** Bumped when the content of an entry changes. */
    private static final String FORMAT = "1";
    private static final String DIGESTS = "digests.txt";
    private static String codeDigest;
    private static String jars;

    final Path dir;
    final Path entry;
    final String key;

    private DeploymentCache(Path dir, String key) {
        this.dir = dir;
        this.key = key;
        this.entry = dir.resolve(key);
    }

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("tester.cache", "true"));
    }

    /** The cache of this deployment ({@code id}: its directory name) on this line, or null when disabled. */
    static DeploymentCache of(Manifest manifest, String id) {
        if (!enabled()) {
            return null;
        }
        String configured = System.getProperty("tester.cache.dir");
        Path root = configured != null ? Path.of(configured) : TesterRuntime.baseWorkDir().resolve("tester-cache");
        return new DeploymentCache(root.toAbsolutePath().normalize().resolve(id), key(manifest));
    }

    boolean exists() {
        return Files.isRegularFile(entry.resolve(DIGESTS));
    }

    /** An exclusive lock of this deployment's cache across JVMs (blocks until the other builder is done). */
    AutoCloseable lock() throws IOException {
        Files.createDirectories(dir);
        FileChannel channel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.lock();
        return () -> {
            lock.release();
            channel.close();
        };
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    /** Writes an entry: {@code writer} fills the directory; then the digests, then the rename; older entries go. */
    void save(Writer writer) throws IOException {
        Files.createDirectories(dir);
        Path tmp = Files.createTempDirectory(dir, key + ".tmp-");
        try {
            writer.write(tmp);
            StringBuilder digests = new StringBuilder();
            for (Path file : files(tmp)) {
                digests.append(digest(file)).append(' ').append(slashed(tmp.relativize(file))).append('\n');
            }
            Files.writeString(tmp.resolve(DIGESTS), digests.toString(), StandardCharsets.UTF_8);
            deleteTree(entry);
            try {
                Files.move(tmp, entry, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, entry);
            }
        } finally {
            deleteTree(tmp);
        }
        try (Stream<Path> others = Files.list(dir)) {
            for (Path other : others.filter(p -> Files.isDirectory(p) && !p.equals(entry))
                    .collect(Collectors.toList())) {
                deleteTree(other);
            }
        }
    }

    /** Fills an entry's directory. */
    interface Writer {
        void write(Path dir) throws IOException;
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    /** Checks the digest of every file of the entry; fails on a missing, extra or changed file. */
    void verify() throws IOException {
        Map<String, String> expected = new TreeMap<>();
        for (String line : Files.readAllLines(entry.resolve(DIGESTS), StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                int space = line.indexOf(' ');
                expected.put(line.substring(space + 1), line.substring(0, space));
            }
        }
        Map<String, String> actual = new TreeMap<>();
        for (Path file : files(entry)) {
            String relative = slashed(entry.relativize(file));
            if (!relative.equals(DIGESTS)) {
                actual.put(relative, digest(file));
            }
        }
        if (!expected.equals(actual)) {
            List<String> differing = new ArrayList<>();
            for (String f : new java.util.TreeSet<>(union(expected.keySet(), actual.keySet()))) {
                if (!java.util.Objects.equals(expected.get(f), actual.get(f))) {
                    differing.add(f);
                }
            }
            throw new IOException("the cache entry " + entry + " is damaged: " + differing);
        }
    }

    void discard() {
        try {
            deleteTree(entry);
        } catch (IOException e) {
            // the next save replaces it
        }
    }

    static void writeObject(Path file, Serializable value) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ObjectOutputStream objects = new ObjectOutputStream(
                new java.io.BufferedOutputStream(out))) {
            objects.writeObject(value);
        }
    }

    static Object readObject(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); ObjectInputStream objects = new ObjectInputStream(
                new java.io.BufferedInputStream(in))) {
            return objects.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    // ---- the key --------------------------------------------------------------------------------------------------

    static String key(Manifest manifest) {
        MessageDigest sha = sha256();
        update(sha, "format " + FORMAT);
        update(sha, "line " + LineSupport.LINE);
        // midPoint, the connector bundles and every other library: the jars of the classpath
        update(sha, "jars " + classpathJars());
        update(sha, "tester " + codeDigest());
        update(sha, "manifest");
        sha.update(read(manifest.file));
        for (Map.Entry<String, Path> file : manifest.sources(LineSupport.LINE).entrySet()) {
            update(sha, "file " + file.getKey());
            sha.update(read(file.getValue()));
        }
        for (Manifest.Target target : manifest.targets.values()) {
            if (target.options.schema() != null) {
                update(sha, "schema " + target.resource);
                sha.update(read(target.options.schema()));
            }
            if (target.initialData != null) {
                update(sha, "initial data " + target.resource);
                sha.update(read(target.initialData));
            }
        }
        for (Map.Entry<String, Path> initial : manifest.initialFiles.entrySet()) {
            for (Path file : files(initial.getValue())) {
                update(sha, "initial file " + initial.getKey() + " " + slashed(initial.getValue().relativize(file)));
                sha.update(read(file));
            }
        }
        // the deployment's own Spring beans (ctx-tester.xml imports them): what they do is in the cached database
        try {
            for (org.springframework.core.io.Resource extension
                    : new org.springframework.core.io.support.PathMatchingResourcePatternResolver(
                            DeploymentCache.class.getClassLoader()).getResources("classpath*:tester/ctx-extension*.xml")) {
                update(sha, "extension " + extension.getURL());
                try (java.io.InputStream in = extension.getInputStream()) {
                    sha.update(in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String schemaDir = System.getProperty("tester.extensionSchemaDir");
        if (schemaDir != null && Files.isDirectory(Path.of(schemaDir))) {
            for (Path xsd : files(Path.of(schemaDir))) {
                update(sha, "schema " + slashed(Path.of(schemaDir).relativize(xsd)));
                sha.update(read(xsd));
            }
        }
        return hex(sha.digest()).substring(0, 32);
    }

    /**
     * Name, size and time of every jar of the class path (found by their manifests, so also behind Surefire's
     * manifest-only jar); directories (the project's classes; the tester's own are in the key apart), Surefire's
     * booter and the jars the tester writes in its working directory are left out.
     */
    private static synchronized String classpathJars() {
        if (jars == null) {
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            try {
                java.util.Enumeration<java.net.URL> manifests = DeploymentCache.class.getClassLoader()
                        .getResources("META-INF/MANIFEST.MF");
                while (manifests.hasMoreElements()) {
                    String url = manifests.nextElement().toString();
                    if (!url.startsWith("jar:file:") || !url.contains("!/")) {
                        continue;
                    }
                    Path jar = Path.of(new java.net.URI(url.substring("jar:".length(), url.indexOf("!/"))));
                    // not Surefire's booter (a new name every run) nor what the tester writes itself (its agent)
                    if (jar.getFileName().toString().startsWith("surefirebooter") || jar.toAbsolutePath()
                            .startsWith(TesterRuntime.baseWorkDir())) {
                        continue;
                    }
                    out.add(jar.getFileName() + ":" + Files.size(jar) + ":" + Files.getLastModifiedTime(jar));
                }
            } catch (IOException | URISyntaxException | RuntimeException e) {
                out.add("unknown " + System.nanoTime());
            }
            jars = String.join(" ", out);
        }
        return jars;
    }

    /** The digest of the tester's own classes (the jar, or every file of the classes directory). */
    private static synchronized String codeDigest() {
        if (codeDigest == null) {
            MessageDigest sha = sha256();
            try {
                Path location = Path.of(DeploymentCache.class.getProtectionDomain().getCodeSource().getLocation()
                        .toURI());
                if (Files.isDirectory(location)) {
                    for (Path file : files(location)) {
                        update(sha, slashed(location.relativize(file)));
                        sha.update(read(file));
                    }
                } else {
                    sha.update(read(location));
                }
            } catch (URISyntaxException | RuntimeException e) {
                update(sha, "unknown " + System.nanoTime());
            }
            codeDigest = hex(sha.digest());
        }
        return codeDigest;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest sha, String text) {
        sha.update((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digest(Path file) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                sha.update(buffer, 0, n);
            }
        }
        return hex(sha.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> List<T> union(java.util.Collection<T> a, java.util.Collection<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String slashed(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    /** A map that serializes (for the parts of an entry). */
    static HashMap<String, Object> map() {
        return new HashMap<>();
    }
}
