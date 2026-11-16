package com.github.wadahiro.midpoint.configtester.provided;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.wadahiro.midpoint.tester.RealSystem;

/**
 * A {@link RealSystem} of the CSV connector: a file of its own the resource reads, saved and restored as its bytes.
 * An example of what a test provides for {@code connector: real}; a test writes the rows itself
 * ({@link #of(String)}, {@link #append(String)}).
 */
public class CsvFileSystem implements RealSystem {

    private static final Map<String, CsvFileSystem> BY_RESOURCE = new ConcurrentHashMap<>();

    private Path file;
    private Path tmp;

    /** The system of the resource of this name. */
    public static CsvFileSystem of(String resourceName) {
        return BY_RESOURCE.get(resourceName);
    }

    @Override
    public void start(String resourceOid, String resourceName) {
        try {
            Path dir = Files.createTempDirectory("csv-" + resourceOid);
            file = dir.resolve("data.csv");
            tmp = Files.createDirectories(dir.resolve("tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BY_RESOURCE.put(resourceName, this);
    }

    @Override
    public Map<String, String> configuration() {
        return Map.of("filePath", file.toString(), "tmpFolder", tmp.toString());
    }

    @Override
    public void loadInitialData(Path initialData) {
        try {
            Files.copy(initialData, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds a line (a row in the file's format). */
    public void append(String line) {
        try {
            Files.writeString(file, line + "\r\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> lines() {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Object capture() {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void restore(Object captured) {
        if (captured == null) {
            return;
        }
        try {
            Files.write(file, (byte[]) captured);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public java.io.Serializable persistentContent() {
        return (byte[]) capture();
    }

    @Override
    public void restorePersistent(java.io.Serializable content) {
        restore(content);
    }

    @Override
    public String accountNamingAttribute() {
        return "empno";
    }
}
