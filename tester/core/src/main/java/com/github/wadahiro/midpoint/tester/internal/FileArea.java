package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wadahiro.midpoint.tester.MappedFiles;
import com.github.wadahiro.midpoint.tester.WrittenFile;
import com.github.wadahiro.midpoint.tester.agent.FileRemap;

/**
 * The mapped directories of {@link MappedFiles}: each maps a directory the configuration names (logical) to one under
 * {@code <workDir>/tester-files/} (physical) with the logical path below it, e.g. {@code /opt/midpoint/var} to
 * {@code target/tester-files/opt/midpoint/var}. The table is published to {@link FileRemap}, which the instrumented
 * {@code File} and {@code Path} constructors of the whole JVM consult ({@link FileAgent}). {@link #scan()} and
 * {@link #written} give the files a driver call wrote; {@link #capture()} / {@link #restore(Object)} follow the
 * database snapshots.
 *
 * Logical paths are handled as strings here: a {@code File} or {@code Path} of one would itself be redirected.
 *
 * midPoint's report output directory ({@code <midpoint.home>/export}) is watched and snapshotted too, with its real
 * path as the logical one.
 */
public final class FileArea implements MappedFiles {

    private static final FileArea INSTANCE = new FileArea();
    private static final String SEPARATOR = File.separator;

    /** A mapped directory: logical (normalized absolute path) and physical. */
    private static final class Root {
        final String logical;
        final Path physical;

        Root(String logical, Path physical) {
            this.logical = logical;
            this.physical = physical;
        }
    }

    /** What {@link #capture()} keeps: the mapped directories and every directory and file under the watched ones. */
    private static final class Snapshot {
        final List<Root> roots;
        final Map<Path, byte[]> files;

        Snapshot(List<Root> roots, Map<Path, byte[]> files) {
            this.roots = roots;
            this.files = files;
        }
    }

    /** {@code <workDir>/tester-files}; null until the runtime starts. */
    private volatile Path base;
    private volatile Path exportDirectory;
    /** Why the redirection does not work on this JVM, or null. */
    private volatile String unavailable;
    /** Innermost (longest) logical path first. */
    private volatile List<Root> roots = List.of();

    private FileArea() {
    }

    public static FileArea get() {
        return INSTANCE;
    }

    /** Called once by the runtime: installs the redirection, starts with nothing mapped and empty directories. */
    synchronized void init(Path workDir, Path midpointHome) throws IOException {
        Path area = workDir.resolve("tester-files").toAbsolutePath().normalize();
        deleteTree(area);
        Files.createDirectories(area);
        exportDirectory = midpointHome.resolve("export").toAbsolutePath().normalize();
        deleteTree(exportDirectory);
        Files.createDirectories(exportDirectory);
        unavailable = FileAgent.install(workDir.toAbsolutePath().normalize());
        if (unavailable != null) {
            System.err.println("Tester: mp.files() is not available: " + unavailable);
        }
        base = area;
        publish(List.of());
    }

    private void publish(List<Root> next) {
        roots = next;
        if (unavailable == null) {
            FileRemap.set(next.stream().map(r -> r.logical).toArray(String[]::new),
                    next.stream().map(r -> r.physical.toString()).toArray(String[]::new), base.toString());
        }
    }

    // ---- MappedFiles ---------------------------------------------------------------------------------------------

    @Override
    public synchronized MappedFiles map(String directory) {
        Path area = requireStarted();
        if (unavailable != null) {
            throw new IllegalStateException("Files cannot be mapped on this JVM: " + unavailable);
        }
        String logical = normalize(directory);
        String areaText = area.toString();
        if (isUnder(logical, areaText) || isUnder(areaText, logical)) {
            throw new IllegalArgumentException("Cannot map " + directory + ": it holds or is in the tester's own "
                    + area);
        }
        for (Root root : roots) {
            if (root.logical.equals(logical)) {
                return this;
            }
        }
        Path physical = physicalRoot(area, logical);
        try {
            Files.createDirectories(physical);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Root> next = new ArrayList<>(roots);
        next.add(new Root(logical, physical));
        next.sort(Comparator.comparingInt((Root r) -> r.logical.length()).reversed());
        publish(List.copyOf(next));
        return this;
    }

    @Override
    public List<String> mapped() {
        return roots.stream().map(r -> r.logical).sorted().collect(Collectors.toList());
    }

    @Override
    public MappedFiles write(String path, String content) {
        return write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public MappedFiles write(String path, byte[] content) {
        Path file = physical(path);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    @Override
    public String read(String path) {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> lines(String path) {
        try {
            return Files.readAllLines(existing(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] bytes(String path) {
        try {
            return Files.readAllBytes(existing(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(physical(path));
    }

    @Override
    public List<String> list(String directory) {
        Path dir = physical(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(this::logical).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The physical file of a path under a mapped directory or the export directory; fails for other paths. */
    @Override
    public Path physical(String path) {
        requireStarted();
        String logical = normalize(path);
        if (isUnder(logical, exportDirectory.toString())) {
            return Paths.get(logical);
        }
        for (Root root : roots) {
            if (isUnder(logical, root.logical)) {
                String below = logical.substring(root.logical.length());
                return Paths.get(root.physical.toString() + below);
            }
        }
        int slash = logical.lastIndexOf(SEPARATOR);
        throw new IllegalArgumentException(path + " is in no mapped directory (mapped: " + mapped()
                + "); map one first: mp.files().map(\"" + (slash > 0 ? logical.substring(0, slash) : logical) + "\")");
    }

    @Override
    public String exportDirectory() {
        requireStarted();
        return exportDirectory.toString();
    }

    private Path existing(String path) {
        Path file = physical(path);
        if (!Files.isRegularFile(file)) {
            String logical = normalize(path);
            int slash = logical.lastIndexOf(SEPARATOR);
            String parent = slash > 0 ? logical.substring(0, slash) : null;
            throw new AssertionError("No file " + path + (parent != null ? "; files under " + parent + ": "
                    + safeList(parent) : ""));
        }
        return file;
    }

    private List<String> safeList(String directory) {
        try {
            return list(directory);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---- what a driver call wrote ----------------------------------------------------------------------------------

    /** The content of every file under the watched directories now (physical path to content). */
    Map<Path, byte[]> scan() {
        Map<Path, byte[]> files = new TreeMap<>();
        for (Path dir : watched()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    files.put(file, Files.readAllBytes(file));
                }
            } catch (IOException | UncheckedIOException e) {
                // a file deleted while walking: not written by this call
            }
        }
        return files;
    }

    /** The files created or changed since the scan, in path order, by their logical paths. */
    List<WrittenFile> written(Map<Path, byte[]> before, Map<Path, byte[]> now) {
        List<WrittenFile> written = new ArrayList<>();
        for (Map.Entry<Path, byte[]> e : now.entrySet()) {
            byte[] old = before.get(e.getKey());
            if (old == null || !Arrays.equals(old, e.getValue())) {
                written.add(new WrittenFile(logical(e.getKey()), e.getValue(), old == null));
            }
        }
        return written;
    }

    /** The files deleted since the scan, by their logical paths. */
    List<String> deleted(Map<Path, byte[]> before, Map<Path, byte[]> now) {
        return before.keySet().stream().filter(p -> !now.containsKey(p)).map(this::logical)
                .collect(Collectors.toList());
    }

    private List<Path> watched() {
        if (base == null) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(exportDirectory);
        roots.forEach(r -> dirs.add(r.physical));
        return dirs;
    }

    /** The logical path of a physical file. */
    private String logical(Path physical) {
        String text = physical.toString();
        for (Root root : roots) {
            String dir = root.physical.toString();
            if (isUnder(text, dir)) {
                return root.logical + text.substring(dir.length());
            }
        }
        return text;
    }

    // ---- snapshots ---------------------------------------------------------------------------------------------

    /** The mapped directories and the content of the watched ones (directories as null content). */
    synchronized Object capture() {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (Path dir : treeRoots()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    files.put(p, Files.isDirectory(p) ? null : Files.readAllBytes(p));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Snapshot(roots, files);
    }

    /** Back to the captured state: other files are deleted, mappings made since are forgotten. */
    synchronized void restore(Object state) {
        Snapshot snapshot = (Snapshot) state;
        try {
            for (Path dir : treeRoots()) {
                deleteTree(dir);
            }
            for (Map.Entry<Path, byte[]> e : snapshot.files.entrySet()) {
                if (e.getValue() == null) {
                    Files.createDirectories(e.getKey());
                } else {
                    Files.createDirectories(e.getKey().getParent());
                    Files.write(e.getKey(), e.getValue());
                }
            }
            Files.createDirectories(base);
            Files.createDirectories(exportDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (snapshot.roots != roots) {
            publish(snapshot.roots);
        }
    }

    /**
     * The files of the watched directories by {@code area/<relative path>} and {@code export/<relative path>}
     * (directories as null), for the deployment cache: another JVM's directories have other absolute paths.
     */
    synchronized java.util.TreeMap<String, byte[]> persistentFiles() {
        java.util.TreeMap<String, byte[]> out = new java.util.TreeMap<>();
        Snapshot snapshot = (Snapshot) capture();
        snapshot.files.forEach((path, content) -> {
            String key = path.startsWith(base) ? "area/" + slashed(base.relativize(path))
                    : "export/" + slashed(exportDirectory.relativize(path));
            out.put(key, content);
        });
        return out;
    }

    /** Back to {@link #persistentFiles()} (the mappings are those made since the last reset). */
    synchronized void restorePersistentFiles(Map<String, byte[]> files) {
        Map<Path, byte[]> absolute = new LinkedHashMap<>();
        files.forEach((key, content) -> absolute.put(key.startsWith("area/")
                ? base.resolve(key.substring("area/".length())) : exportDirectory.resolve(key.substring("export/".length())),
                content));
        restore(new Snapshot(roots, absolute));
    }

    private static String slashed(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private List<Path> treeRoots() {
        Path area = base;
        return area == null ? List.of() : List.of(area, exportDirectory);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Path requireStarted() {
        Path area = base;
        if (area == null) {
            throw new IllegalStateException("The tester's midPoint has not started");
        }
        return area;
    }

    /**
     * An absolute path as text, without {@code .}, {@code ..}, repeated or trailing separators, never through
     * {@code File} or {@code Path} (which would redirect it).
     */
    public static String normalize(String path) {
        String text = path.replace('/', File.separatorChar);
        String root;
        if (text.startsWith(SEPARATOR)) {
            root = "";
        } else if (text.length() > 2 && text.charAt(1) == ':' && text.substring(2).startsWith(SEPARATOR)) {
            root = text.substring(0, 2);
            text = text.substring(2);
        } else {
            throw new IllegalArgumentException("Not an absolute path: " + path);
        }
        Deque<String> names = new ArrayDeque<>();
        for (String name : text.split(java.util.regex.Pattern.quote(SEPARATOR))) {
            if (name.isEmpty() || ".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                names.pollLast();
            } else {
                names.addLast(name);
            }
        }
        return names.isEmpty() ? root + SEPARATOR : root + SEPARATOR + String.join(SEPARATOR, names);
    }

    /** {@code path} is {@code dir} or below it, per path segment. */
    private static boolean isUnder(String path, String dir) {
        if (dir.endsWith(SEPARATOR)) {
            return path.startsWith(dir);
        }
        return path.equals(dir) || path.startsWith(dir + SEPARATOR);
    }

    /** {@code <area>/opt/midpoint/var} for {@code /opt/midpoint/var}; a Windows drive becomes a directory. */
    private static Path physicalRoot(Path area, String logical) {
        String below = logical.length() > 1 && logical.charAt(1) == ':'
                ? logical.charAt(0) + logical.substring(2) : logical.substring(1);
        return area.resolve(below);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }
}
