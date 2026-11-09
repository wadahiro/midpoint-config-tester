package com.github.wadahiro.midpoint.tester;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;

/**
 * Java code a script calls that does its own file I/O from path strings, like a custom library or Apache Commons IO:
 * the JVM-level redirection of {@code mp.files()} covers it (FilesTest.javaCodeCalledByAScript).
 */
public final class FileUtility {

    private FileUtility() {
    }

    /** Writes one file with each API under {@code dir} (strings joined here, not in the script); returns the names. */
    public static List<String> writeAll(String dir, String text) throws IOException {
        Files.createDirectories(Paths.get(dir));
        try (FileWriter writer = new FileWriter(dir + "/file-writer.txt")) {
            writer.write(text);
        }
        try (FileOutputStream out = new FileOutputStream(dir + "/stream.txt")) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        try (RandomAccessFile file = new RandomAccessFile(dir + "/random-access.txt", "rw")) {
            file.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(Path.of(dir + "/nio.txt"), text);
        // resolved from a parent that is not mapped itself (the root directory)
        Files.write(Paths.get("/").resolve(dir.substring(1) + "/resolved.txt"), text.getBytes(StandardCharsets.UTF_8));
        FileUtils.writeStringToFile(new File(dir, "commons-io.txt"), text, StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(Paths.get(dir))) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    /** The first line of the file, read with {@code FileReader}. */
    public static String firstLine(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        }
    }

    /** Whether the path exists on the real file system, asked of a process (the JVM itself sees mapped paths). */
    public static boolean existsOutsideTheJvm(String path) throws IOException, InterruptedException {
        return new ProcessBuilder("test", "-e", path).start().waitFor() == 0;
    }
}
