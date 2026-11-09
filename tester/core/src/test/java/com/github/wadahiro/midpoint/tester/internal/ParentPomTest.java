package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The parent of config repositories of the line ({@code parent/midpoint-<line>}) manages every midPoint module the
 * line's tester depends on at {@code midpoint.version}: midPoint's BOM does not manage its own modules, so a missing
 * one would come at the version the tester was built with, whatever the repository's midpoint.version (a support
 * build, a fork). No midPoint runtime.
 */
class ParentPomTest {

    private static final Path TESTER = Path.of(System.getProperty("tester.dir", ".."));

    @Test
    void theParentManagesTheTestersMidPointModulesAtMidPointVersion() throws IOException {
        Path line = TESTER.resolve("midpoint-" + LineSupport.LINE).resolve("pom.xml");
        List<String> modules = new ArrayList<>(midPointDependencies(line));
        Element parent = Xml.child(Xml.parse(read(line)).getDocumentElement(), "parent");
        Element relativePath = parent != null ? Xml.child(parent, "relativePath") : null;
        if (relativePath != null && relativePath.getTextContent().contains("base-")) {
            modules.addAll(midPointDependencies(line.getParent().resolve(relativePath.getTextContent().trim())));
        }

        Map<String, String> managed = managed(TESTER.resolve("../parent/midpoint-" + LineSupport.LINE + "/pom.xml"));
        assertThat(modules).isNotEmpty();
        for (String module : modules) {
            assertThat(managed).as("parent/midpoint-%s manages %s", LineSupport.LINE, module)
                    .containsEntry(module, "${midpoint.version}");
        }
    }

    /** groupId:artifactId of the POM's midPoint dependencies (not those of its dependency management). */
    private static List<String> midPointDependencies(Path pom) throws IOException {
        Element project = Xml.parse(read(pom)).getDocumentElement();
        Element dependencies = Xml.child(project, "dependencies");
        List<String> out = new ArrayList<>();
        if (dependencies != null) {
            for (Element dependency : Xml.children(dependencies, "dependency")) {
                String id = id(dependency);
                if (id.startsWith("com.evolveum.midpoint") && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** groupId:artifactId -> version of the POM's dependency management. */
    private static Map<String, String> managed(Path pom) throws IOException {
        Element management = Xml.child(Xml.parse(read(pom)).getDocumentElement(), "dependencyManagement");
        Map<String, String> out = new LinkedHashMap<>();
        for (Element dependency : Xml.children(Xml.child(management, "dependencies"), "dependency")) {
            Element version = Xml.child(dependency, "version");
            out.put(id(dependency), version != null ? version.getTextContent().trim() : null);
        }
        return out;
    }

    private static String id(Element dependency) {
        return Xml.child(dependency, "groupId").getTextContent().trim() + ":"
                + Xml.child(dependency, "artifactId").getTextContent().trim();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
