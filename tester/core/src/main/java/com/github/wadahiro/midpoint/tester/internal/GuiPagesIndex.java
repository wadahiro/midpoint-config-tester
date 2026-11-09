package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Writes the index of the GUI's pages ({@code tester/gui-pages.tsv}, read by {@code GuiPages}) from the GUI's jars, at
 * build time: what {@code DescriptorLoader} reads from the {@code @PageDescriptor}s, read from the class files without
 * loading them (they need Wicket), and the URL mappings the GUI's authorization evaluator adds to them
 * ({@code PageUrlMapping} of 4.0 and 4.4, {@code EndPointsUrlMapping} of 4.8+), loaded from the jars.
 *
 * <p>{@code GuiPagesIndex <output file> <jar or directory of jars>...}. A row is
 * {@code kind TAB pattern TAB actions TAB source}: kind
 * {@code page} (the descriptor's actions; the GUI adds {@code #guiAll}), {@code page-no-gui-all} (a page with
 * {@code #noAccess}: no {@code #guiAll}), {@code permit} (any user, a login page among them) or {@code static} (a URL
 * mapping, its actions as midPoint has them).</p>
 */
public final class GuiPagesIndex {

    private static final String NO_ACCESS =
            "http://midpoint.evolveum.com/xml/ns/public/security/authorization-3#noAccess";
    private static final String[] MAPPINGS = {"com.evolveum.midpoint.web.security.PageUrlMapping",
            "com.evolveum.midpoint.authentication.impl.util.EndPointsUrlMapping"};

    private GuiPagesIndex() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<File> jars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            File arg = new File(args[i]);
            File[] inside = arg.isDirectory() ? arg.listFiles((dir, name) -> name.endsWith(".jar")) : null;
            if (inside != null) {
                java.util.Arrays.sort(inside);
                jars.addAll(List.of(inside));
            } else {
                jars.add(arg);
            }
        }
        // pattern -> rows of it, sorted: the index is the same for the same jars
        Map<String, Set<String>> rows = new TreeMap<>();
        int pages = 0;
        for (File jar : jars) {
            pages += pages(jar, rows);
        }
        String mapping = mappings(jars, rows);
        StringBuilder sb = new StringBuilder("# the GUI's pages, by GuiPagesIndex from");
        jars.forEach(jar -> sb.append(' ').append(jar.getName()));
        sb.append(": ").append(pages).append(" page descriptors")
                .append(mapping != null ? ", " + mapping : ", no URL mapping found").append('\n');
        rows.values().forEach(set -> set.forEach(row -> sb.append(row).append('\n')));
        Files.createDirectories(out.getParent());
        if (!Files.exists(out) || !Files.readString(out, StandardCharsets.UTF_8).equals(sb.toString())) {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        }
        System.out.println("Tester: " + out + ": " + pages + " page descriptors"
                + (mapping != null ? ", " + mapping : ""));
    }

    private static int pages(File jar, Map<String, Set<String>> rows) throws IOException {
        int count = 0;
        // the annotation types are in midPoint's jars on the classpath (exec:java's loader, not the system one)
        ClassFileLocator locator = new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(jar),
                ClassFileLocator.ForClassLoader.of(GuiPagesIndex.class.getClassLoader()));
        TypePool pool = TypePool.Default.of(locator);
        try (JarFile file = new JarFile(jar)) {
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("com/evolveum/midpoint/") || !name.endsWith(".class")) {
                    continue;
                }
                TypeDescription type = pool.describe(name.substring(0, name.length() - 6).replace('/', '.')).resolve();
                for (AnnotationDescription annotation : type.getDeclaredAnnotations()) {
                    if (annotation.getAnnotationType().getSimpleName().equals("PageDescriptor")) {
                        page(type.getName(), annotation, rows);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** As {@code DescriptorLoader.loadActions}. */
    private static void page(String page, AnnotationDescription descriptor, Map<String, Set<String>> rows) {
        List<String> urls = urls(descriptor);
        if (bool(descriptor, "permitAll") || bool(descriptor, "loginPage")) {
            urls.forEach(url -> add(rows, "permit", url, List.of(), page));
            if (bool(descriptor, "permitAll")) {
                return;
            }
        }
        List<String> actions = new ArrayList<>();
        AnnotationValue<?, ?> value = descriptor.getValue("action");
        for (AnnotationDescription action : (AnnotationDescription[]) value.resolve()) {
            String uri = (String) action.getValue("actionUri").resolve();
            actions.add(uri);
            if (NO_ACCESS.equals(uri)) {
                break;
            }
        }
        if (actions.isEmpty()) {
            // a page without authorizations: denied unless another pattern matches
            return;
        }
        String kind = actions.contains(NO_ACCESS) ? "page-no-gui-all" : "page";
        urls.forEach(url -> add(rows, kind, url, actions, page));
    }

    /** As {@code DescriptorLoader.foreachUrl}: 4.0's {@code url} and {@code urls}' match or mount URL. */
    private static List<String> urls(AnnotationDescription descriptor) {
        List<String> out = new ArrayList<>();
        if (descriptor.getAnnotationType().getDeclaredMethods().filter(m -> m.getName().equals("url")).size() > 0) {
            for (String url : (String[]) descriptor.getValue("url").resolve()) {
                out.add(prefix(url));
            }
        }
        for (AnnotationDescription url : (AnnotationDescription[]) descriptor.getValue("urls").resolve()) {
            String match = (String) url.getValue("matchUrlForSecurity").resolve();
            out.add(match == null || match.isEmpty() ? prefix((String) url.getValue("mountUrl").resolve()) : match);
        }
        return out;
    }

    private static String prefix(String url) {
        return url + (url.endsWith("/") ? "" : "/") + "**";
    }

    private static boolean bool(AnnotationDescription descriptor, String property) {
        if (descriptor.getAnnotationType().getDeclaredMethods().filter(m -> m.getName().equals(property)).isEmpty()) {
            return false;
        }
        return (Boolean) descriptor.getValue(property).resolve();
    }

    /** The URL mappings of the authorization evaluator, loaded; null when none is in the jars. */
    private static String mappings(List<File> jars, Map<String, Set<String>> rows) throws Exception {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = jars.get(i).toURI().toURL();
        }
        try (URLClassLoader loader = new URLClassLoader(urls, GuiPagesIndex.class.getClassLoader())) {
            for (String name : MAPPINGS) {
                Class<?> mapping;
                try {
                    mapping = Class.forName(name, true, loader);
                } catch (ClassNotFoundException e) {
                    continue;
                } catch (LinkageError e) {
                    System.err.println("[WARNING] Tester: " + name + " not loaded (" + e + "): the index has no URL"
                            + " mappings");
                    return null;
                }
                Method url = mapping.getMethod("getUrl");
                Method action = mapping.getMethod("getAction");
                for (Object constant : mapping.getEnumConstants()) {
                    List<String> actions = new ArrayList<>();
                    for (Object value : (Object[]) action.invoke(constant)) {
                        actions.add((String) value.getClass().getMethod("getValue").invoke(value));
                    }
                    add(rows, "static", (String) url.invoke(constant), actions,
                            mapping.getSimpleName() + "." + constant);
                }
                return mapping.getSimpleName();
            }
        }
        return null;
    }

    private static void add(Map<String, Set<String>> rows, String kind, String pattern, List<String> actions,
            String source) {
        Set<String> set = rows.computeIfAbsent(pattern, p -> new LinkedHashSet<>());
        set.add(kind + "\t" + pattern + "\t" + String.join(" ", actions) + "\t" + source);
    }
}
