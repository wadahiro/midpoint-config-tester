package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import com.github.wadahiro.midpoint.tester.mirror.connector.MirrorConnector;

/**
 * Packs the mirror connector into a ConnId bundle jar for midPoint's connector scan directory. midPoint discovers
 * bundles only as jars with ConnId manifest entries, so this is built at runtime from the classes on the classpath
 * (a directory during this project's own build, a jar otherwise).
 *
 * Only the connector package goes into the bundle. The in-memory store ({@code ...tester.mirror}) stays outside,
 * so the bundle class loader resolves it from the application class loader and the tests share it with the connector.
 */
final class MirrorBundle {

    static final String BUNDLE_NAME = "com.github.wadahiro.midpoint.tester.mirror";
    static final String BUNDLE_VERSION = "1.0";
    static final String CONNECTOR_TYPE = MirrorConnector.class.getName();

    private static final String PACKAGE_PATH = MirrorConnector.class.getPackageName().replace('.', '/') + "/";

    private MirrorBundle() {
    }

    /** Writes {@code <dir>/mirror-connector.jar}. */
    static void write(Path dir) {
        try {
            Files.createDirectories(dir);
            Manifest manifest = new Manifest();
            Attributes main = manifest.getMainAttributes();
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            main.putValue("ConnectorBundle-FrameworkVersion", "1.4");
            main.putValue("ConnectorBundle-Name", BUNDLE_NAME);
            main.putValue("ConnectorBundle-Version", BUNDLE_VERSION);
            try (OutputStream file = Files.newOutputStream(dir.resolve("mirror-connector.jar"));
                    JarOutputStream jar = new JarOutputStream(file, manifest)) {
                for (String entry : entries()) {
                    jar.putNextEntry(new JarEntry(entry));
                    try (InputStream in = MirrorBundle.class.getClassLoader().getResourceAsStream(entry)) {
                        in.transferTo(jar);
                    }
                    jar.closeEntry();
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Cannot build the mirror connector bundle", e);
        }
    }

    /** Resource names of the connector package (classes, Messages.properties). */
    private static Set<String> entries() throws IOException, URISyntaxException {
        Set<String> out = new LinkedHashSet<>();
        Path location = Path.of(MirrorConnector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            // classes and resources may live in different directories (target/classes is both here)
            Enumeration<java.net.URL> dirs = MirrorBundle.class.getClassLoader().getResources(PACKAGE_PATH);
            while (dirs.hasMoreElements()) {
                java.net.URL url = dirs.nextElement();
                if ("file".equals(url.getProtocol())) {
                    try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                        files.filter(Files::isRegularFile)
                                .forEach(f -> out.add(PACKAGE_PATH + f.getFileName()));
                    }
                }
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                jar.stream()
                        .map(JarEntry::getName)
                        .filter(n -> n.startsWith(PACKAGE_PATH) && !n.endsWith("/")
                                && n.indexOf('/', PACKAGE_PATH.length()) < 0)
                        .forEach(out::add);
            }
        }
        if (out.stream().noneMatch(n -> n.endsWith("MirrorConnector.class"))) {
            throw new IllegalStateException("Mirror connector classes not found at " + location);
        }
        return out;
    }
}
