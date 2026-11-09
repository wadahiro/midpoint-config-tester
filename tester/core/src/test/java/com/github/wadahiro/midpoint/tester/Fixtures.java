package com.github.wadahiro.midpoint.tester;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.github.wadahiro.midpoint.tester.internal.TesterRuntime;

/**
 * The fixtures of the shared tests ({@code tester/core/src/test/resources/fixtures}), which every line module runs from
 * its own directory: the surefire configuration gives their place as {@code tester.fixtures}.
 *
 * The fixtures are written for the newest format. Where an older line cannot read a file (e.g. a resource with
 * {@code delineation}, 4.6+), {@code fixtures-<line>/} next to it holds a variant with the same relative path for that
 * line and the older ones: the variant of the oldest such directory that is not older than the running line wins
 * ({@link #of}); without one, the common file is used.
 */
final class Fixtures {

    static final File ROOT = new File(System.getProperty("tester.fixtures", "src/test/resources/fixtures"));
    /** The namespace of midPoint's objects, for XML written in a test. */
    static final String NS = "xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'";

    private Fixtures() {
    }

    static File dir(String name) {
        return new File(ROOT, name);
    }

    /** {@code file} (under {@link #ROOT}) or its variant for the running line. */
    static File of(MidPoint mp, File file) {
        String root = ROOT.getPath() + File.separator;
        if (!file.getPath().startsWith(root)) {
            return file;
        }
        String relative = file.getPath().substring(root.length());
        File[] dirs = ROOT.getAbsoluteFile().getParentFile()
                .listFiles(f -> f.isDirectory() && f.getName().startsWith(ROOT.getName() + "-"));
        String best = null;
        File variant = null;
        for (File dir : dirs != null ? dirs : new File[0]) {
            String line = dir.getName().substring(ROOT.getName().length() + 1);
            File candidate = new File(dir, relative);
            if (Lines.compare(line, mp.line()) >= 0 && candidate.isFile()
                    && (best == null || Lines.compare(line, best) < 0)) {
                best = line;
                variant = candidate;
            }
        }
        return variant != null ? variant : file;
    }

    /**
     * {@code file} in the oldest format the tests have for it: its variant for the running line as {@link #of} gives
     * it; on a line newer than every variant of the file (e.g. the 4.4 format on 4.8+, which still read it), the newest
     * variant; the common file when there is none.
     */
    static File legacy(MidPoint mp, File file) {
        File variant = of(mp, file);
        if (!variant.equals(file)) {
            return variant;
        }
        String root = ROOT.getPath() + File.separator;
        if (!file.getPath().startsWith(root)) {
            return file;
        }
        String relative = file.getPath().substring(root.length());
        File[] dirs = ROOT.getAbsoluteFile().getParentFile()
                .listFiles(f -> f.isDirectory() && f.getName().startsWith(ROOT.getName() + "-"));
        String best = null;
        for (File dir : dirs != null ? dirs : new File[0]) {
            String line = dir.getName().substring(ROOT.getName().length() + 1);
            File candidate = new File(dir, relative);
            if (candidate.isFile() && (best == null || Lines.compare(line, best) > 0)) {
                best = line;
                variant = candidate;
            }
        }
        return variant;
    }

    /**
     * A directory of fixtures (for {@code importObjects}) with the variants of the running line: the common directory
     * itself, or a copy (under the module's {@code target}) with the variant files in place of the common ones.
     */
    static File dir(MidPoint mp, String name) {
        File dir = dir(name);
        File[] files = dir.listFiles(File::isFile);
        if (files == null) {
            return dir;
        }
        boolean varied = false;
        for (File file : files) {
            varied |= !of(mp, file).equals(file);
        }
        if (!varied) {
            return dir;
        }
        Path copy = TesterRuntime.workDirOfThisJvm().resolve("fixtures-" + mp.line()).resolve(name);
        try {
            Files.createDirectories(copy);
            for (File file : files) {
                Files.copy(of(mp, file).toPath(), copy.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return copy.toFile();
    }
}
