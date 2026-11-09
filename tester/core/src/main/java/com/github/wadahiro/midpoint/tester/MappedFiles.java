package com.github.wadahiro.midpoint.tester;

import java.nio.file.Path;
import java.util.List;

/**
 * The files scripts read and write ({@link MidPoint#files()}), kept away from the real file system.
 *
 * A test maps a directory the configuration names (e.g. {@code /opt/midpoint/var}) to a directory of its own under
 * {@code <workDir>/tester-files/}. Every {@code java.io.File} and every {@code Path} of the default (Unix) file system
 * constructed in the JVM under the mapped directory then points into the test's directory: Groovy scripts, the Java
 * code they call (custom libraries, Commons IO, midPoint) and the test itself, so
 * {@code new File("/opt/midpoint/var/export/users.csv").withWriter { ... }} writes there. The tester attaches a Java
 * agent for it (or uses the one given with {@code -javaagent}). Paths outside mapped directories are left as they
 * are.
 *
 * <pre>{@code
 * mp.files().map("/opt/midpoint/var");
 * mp.files().write("/opt/midpoint/var/import/hr.csv", "id,name\n1001,John Doe\n");
 * Run run = mp.bulkAction(xml);
 * assertThat(run.fileWritten("/opt/midpoint/var/export/users.csv").lines()).contains("1001;JOHN DOE");
 * }</pre>
 *
 * midPoint's own report output directory ({@code <midpoint.home>/export}, see {@link #exportDirectory()}) is watched
 * too: an export's file is in {@link Run#filesWritten()}.
 *
 * Mapped directories and their content follow the test lifecycle: what a {@code @BeforeAll} method mapped and wrote is
 * there in every test method of the class; what a test method mapped or wrote is gone after it.
 */
public interface MappedFiles {

    /**
     * Maps this directory (absolute, as the configuration names it) and everything under it to an empty directory of
     * the test; creating it is not needed. Mapping a directory twice keeps its content.
     */
    MappedFiles map(String directory);

    /** The mapped directories, as the configuration names them. */
    List<String> mapped();

    /** Writes the file (UTF-8) under a mapped directory, creating its parents, e.g. an input file a script reads. */
    MappedFiles write(String path, String content);

    MappedFiles write(String path, byte[] content);

    /** The content of the file (UTF-8); fails if there is none. */
    String read(String path);

    /** The lines of the file (UTF-8). */
    List<String> lines(String path);

    byte[] bytes(String path);

    boolean exists(String path);

    /** The files under the directory (at any depth), by the paths the configuration would use, sorted. */
    List<String> list(String directory);

    /** Where the file really is (e.g. to open it with a CSV parser of the test). */
    Path physical(String path);

    /** midPoint's report output directory ({@code <midpoint.home>/export}), a real directory of the tester. */
    String exportDirectory();
}
