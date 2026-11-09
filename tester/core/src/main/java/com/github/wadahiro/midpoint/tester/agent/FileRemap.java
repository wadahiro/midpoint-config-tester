package com.github.wadahiro.midpoint.tester.agent;

import java.nio.charset.StandardCharsets;

/**
 * The table of mapped directories that the instrumented {@code java.io.File} and {@code sun.nio.fs.UnixPath}
 * constructors consult. Loaded by the bootstrap class loader (the tester appends a jar with only this class to its
 * search path before instrumenting), so that JDK classes can call it; the tester's own code reaches the same class
 * through parent-first delegation.
 *
 * Called on every construction of a {@code File} or a Unix {@code Path} in the JVM: it must stay cheap (an empty table
 * is one volatile read), must not construct files or paths itself, and only rewrites absolute paths under a mapped
 * directory. Not an API.
 */
public final class FileRemap {

    /** Mapped directories as normalized absolute paths, the innermost first, and their physical directories. */
    private static volatile Table table = new Table(new String[0], new String[0], null);

    private FileRemap() {
    }

    private static final class Table {
        final String[] logical;
        final String[] physical;
        final byte[][] logicalBytes;
        final byte[][] physicalBytes;
        /** The tester's own directory: a path under it is physical and never rewritten again. */
        final String area;

        Table(String[] logical, String[] physical, String area) {
            this.logical = logical;
            this.physical = physical;
            this.area = area;
            logicalBytes = new byte[logical.length][];
            physicalBytes = new byte[physical.length][];
            for (int i = 0; i < logical.length; i++) {
                logicalBytes[i] = logical[i].getBytes(StandardCharsets.UTF_8);
                physicalBytes[i] = physical[i].getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Replaces the table (by the tester): {@code logical[i]} is mapped to {@code physical[i]}; ordered innermost
     * first; {@code area} is the directory holding the physical ones.
     */
    public static void set(String[] logical, String[] physical, String area) {
        table = new Table(logical.clone(), physical.clone(), area);
    }

    /** Whether any directory is mapped. */
    public static boolean active() {
        return table.logical.length > 0;
    }

    /** The physical path of a {@code File}'s (normalized) path under a mapped directory, else null. */
    public static String remap(String path) {
        Table t = table;
        if (t.logical.length == 0 || path == null || path.isEmpty() || !isAbsolute(path)) {
            return null;
        }
        if (t.area != null && under(path, t.area)) {
            return null;
        }
        for (int i = 0; i < t.logical.length; i++) {
            if (under(path, t.logical[i])) {
                return t.physical[i] + path.substring(t.logical[i].length());
            }
        }
        return null;
    }

    /** The same for the bytes of a Unix {@code Path} (UTF-8, the encoding the JDK uses for file names here). */
    public static byte[] remap(byte[] path) {
        Table t = table;
        if (t.logical.length == 0 || path == null || path.length == 0 || path[0] != '/') {
            return null;
        }
        for (int i = 0; i < t.logical.length; i++) {
            byte[] root = t.logicalBytes[i];
            if (under(path, root)) {
                byte[] physical = t.physicalBytes[i];
                byte[] out = new byte[physical.length + path.length - root.length];
                System.arraycopy(physical, 0, out, 0, physical.length);
                System.arraycopy(path, root.length, out, physical.length, path.length - root.length);
                return out;
            }
        }
        return null;
    }

    /**
     * The arguments of a command the JVM starts ({@code ProcessBuilder.start}: a shell script of midPoint, a Groovy
     * {@code "...".execute()}) with each mapped directory written in them replaced by its physical one; null if none
     * is. A mapped directory counts where it starts a path (at the start of the argument or after a character that
     * cannot be part of a path: a space, a quote, {@code =}, {@code ;}...) and ends a path segment.
     */
    public static java.util.List<String> remapCommand(java.util.List<String> command) {
        Table t = table;
        if (t.logical.length == 0 || command == null) {
            return null;
        }
        java.util.List<String> out = null;
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            String mapped = arg != null ? remapText(t, arg) : null;
            if (mapped != null) {
                if (out == null) {
                    out = new java.util.ArrayList<>(command);
                }
                out.set(i, mapped);
            }
        }
        return out;
    }

    private static String remapText(Table t, String text) {
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '/' && (i == 0 || !pathChar(text.charAt(i - 1)))
                    && (t.area == null || !text.startsWith(t.area, i))) {
                for (int r = 0; r < t.logical.length; r++) {
                    String root = t.logical[r];
                    int end = i + root.length();
                    if (text.startsWith(root, i) && (end == text.length() || !pathChar(text.charAt(end))
                            || text.charAt(end) == '/')) {
                        if (out == null) {
                            out = new StringBuilder(text.length() + 64);
                        }
                        out.append(text, copied, i).append(t.physical[r]);
                        copied = end;
                        i = end - 1;
                        break;
                    }
                }
            }
            i++;
        }
        return out == null ? null : out.append(text, copied, text.length()).toString();
    }

    /** A character of a path name (not one that ends it in a shell command). */
    private static boolean pathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '-' || c == '_' || c == '~' || c == '+'
                || c == '@' || c == '%' || c == ',' || c == ':' || c > 127;
    }

    private static boolean isAbsolute(String path) {
        char c = path.charAt(0);
        return c == '/' || c == '\\' || path.length() > 2 && path.charAt(1) == ':';
    }

    /** {@code path} is {@code root} or below it (per path segment: {@code /a/bc} is not below {@code /a/b}). */
    private static boolean under(String path, String root) {
        if (!path.startsWith(root)) {
            return false;
        }
        if (path.length() == root.length()) {
            return true;
        }
        char c = path.charAt(root.length());
        return c == '/' || c == '\\';
    }

    private static boolean under(byte[] path, byte[] root) {
        if (path.length < root.length) {
            return false;
        }
        for (int i = root.length - 1; i >= 0; i--) {
            if (path[i] != root[i]) {
                return false;
            }
        }
        return path.length == root.length || path[root.length] == '/';
    }
}
