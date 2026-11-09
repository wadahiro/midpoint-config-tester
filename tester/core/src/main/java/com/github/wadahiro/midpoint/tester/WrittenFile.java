package com.github.wadahiro.midpoint.tester;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A file a driver call created or changed ({@link Run#filesWritten()}): in a mapped directory
 * ({@link MappedFiles#map}) by the path the configuration uses, or in midPoint's report output directory.
 */
public final class WrittenFile {

    private final String path;
    private final byte[] content;
    private final boolean created;

    public WrittenFile(String path, byte[] content, boolean created) {
        this.path = path;
        this.content = content.clone();
        this.created = created;
    }

    /** The path as the configuration names it (e.g. {@code /opt/midpoint/var/export/users.csv}). */
    public String path() {
        return path;
    }

    /** The file name, without its directory. */
    public String name() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    /** Whether the file did not exist before the call (else it was changed). */
    public boolean created() {
        return created;
    }

    public byte[] bytes() {
        return content.clone();
    }

    /** The content as UTF-8 text. */
    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }

    /** The lines of the UTF-8 text. */
    public List<String> lines() {
        String text = text();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = Arrays.asList(text.split("\r?\n", -1));
        // a final line break ends the last line, it does not start an empty one
        return text.endsWith("\n") ? lines.subList(0, lines.size() - 1) : lines;
    }

    @Override
    public String toString() {
        return (created ? "created " : "changed ") + path + " (" + content.length + " bytes)";
    }
}
