package com.github.wadahiro.midpoint.tester;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.util.AntPathMatcher;

/**
 * The GUI's pages of this line by URL, and what the GUI's authorization evaluator requires to open one, from the index
 * the build writes from the GUI's jars ({@code tester/gui-pages.tsv}, {@code GuiPagesIndex}). As
 * {@code MidPointGuiAuthorizationEvaluator.decide}: a page any user may open ({@code permitAll}, login pages) is
 * allowed; else the actions of every pattern that matches the path (case-insensitive Ant patterns), the pages'
 * {@code @PageDescriptor} actions with {@code #guiAll}; no pattern, denied.
 */
public final class GuiPages {

    /** What opening a path requires. */
    public static final class Required {
        private final boolean permitted;
        private final Set<String> actions;
        private final List<String> matches;

        Required(boolean permitted, Set<String> actions, List<String> matches) {
            this.permitted = permitted;
            this.actions = actions;
            this.matches = matches;
        }

        /** Any user opens it (a login page, {@code permitAll}). */
        public boolean permitted() {
            return permitted;
        }

        /** One of them allowed and none denied opens it; empty when no page matches (denied). */
        public Set<String> actions() {
            return actions;
        }

        /** The matching rows, as the failure messages show them: pattern and page. */
        public List<String> matches() {
            return matches;
        }
    }

    private static final String RESOURCE = "tester/gui-pages.tsv";
    private static GuiPages loaded;

    private final List<String[]> rows;
    private final AntPathMatcher matcher = new AntPathMatcher();

    private GuiPages(List<String[]> rows) {
        this.rows = rows;
        matcher.setCaseSensitive(false);
    }

    /** The index of this line; fails when the build wrote none (the lines after 4.8 have none yet). */
    public static synchronized GuiPages of() {
        if (loaded == null) {
            InputStream in = GuiPages.class.getClassLoader().getResourceAsStream(RESOURCE);
            if (in == null) {
                throw new IllegalStateException("This line has no index of the GUI's pages (" + RESOURCE
                        + "): its module does not copy the GUI's jars (tester.gui.skip)");
            }
            List<String[]> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (!line.startsWith("#") && !line.isEmpty()) {
                        rows.add(line.split("\t", -1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            loaded = new GuiPages(rows);
        }
        return loaded;
    }

    /**
     * What opening the path requires: the path after the GUI's context ({@code /admin/users},
     * {@code /admin/user/<oid>}), without a query. {@code guiAll} are the {@code #guiAll} actions of the line,
     * which the GUI adds to the actions of a page's descriptor.
     */
    public Required required(String path, Set<String> guiAll) {
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        Set<String> actions = new LinkedHashSet<>();
        List<String> matches = new ArrayList<>();
        boolean permitted = false;
        for (String[] row : rows) {
            if (!matcher.match(row[1], bare)) {
                continue;
            }
            matches.add(row[1] + " (" + row[3] + ")");
            switch (row[0]) {
                case "permit":
                    permitted = true;
                    break;
                case "page":
                    actions.addAll(actions(row[2]));
                    actions.addAll(guiAll);
                    break;
                default:
                    // page-no-gui-all, static: as midPoint has them
                    actions.addAll(actions(row[2]));
            }
        }
        return new Required(permitted, actions, matches);
    }

    /**
     * The GUI's pages that need an authorization, each pattern once with where it comes from (the page class, or the
     * URL mapping), in the index's order: not the pages any user opens, nor the evaluator's mappings outside the
     * GUI's pages (REST, actuator, Wicket's own URLs).
     */
    public List<String[]> pages() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] row : rows) {
            if (row[0].equals("permit") || !seen.add(row[1])) {
                continue;
            }
            if (row[0].equals("static") && !row[1].startsWith("/admin/") && !row[1].startsWith("/self/")) {
                continue;
            }
            out.add(new String[] {row[1], row[3]});
        }
        return out;
    }

    private static List<String> actions(String cell) {
        return cell.isEmpty() ? List.of() : Arrays.asList(cell.split(" "));
    }
}
