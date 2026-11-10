package com.github.wadahiro.midpoint.configtester;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.namespace.QName;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.RelationRegistry;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.CompiledAuthorization;
import com.github.wadahiro.midpoint.tester.GuiPages;
import com.github.wadahiro.midpoint.tester.GuiProfile;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.internal.TesterRuntime;

/**
 * Checks an authorization matrix ({@link AuthzMatrix}, {@code authz-matrix.yaml}, see {@link AuthzMatrixSpec}): for
 * each persona (a user it creates with the roles, orgs and archetype, or an existing user) and each row (a check: open
 * a page by URL; search, get, read, add, modify, delete a sample object; assign it a target or unassign it, as the
 * clockwork authorizes it ({@link Actor#canAssign}); read or modify an item in its form, as the rows are written or
 * added with {@code auto}), what midPoint lets the
 * persona do, against the expected value of the matrix's rules. One test per cell, by persona; all cells are checked
 * in one test of the scenario. Writes an HTML report of the matrix, expected and actual, per line
 * ({@code <workDir>/authz-matrix/<file>-<line>.html}).
 *
 * <pre>{@code
 * @AuthzMatrix("classpath:authz-matrix.yaml")
 * class AuthzMatrixCheck extends AuthzMatrixTest {
 * }
 * }</pre>
 *
 * An item row of an object the persona may not get is not applicable ({@code -}): it matches an expected N. The
 * note of the rule that gave a cell its expected value is shown with the cell (the report marks such cells).
 */
public abstract class AuthzMatrixTest {

    private static final String MODEL = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-model-3#";
    private static final String ORG = "http://midpoint.evolveum.com/xml/ns/public/common/org-3";

    private static final String UI = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3#";

    /** The checked cell. */
    static final class Cell {
        final AuthzMatrixSpec.Persona persona;
        final AuthzMatrixSpec.Row row;
        final String expected;
        final AuthzMatrixSpec.Rule rule;
        final boolean critical;
        String actual;
        String detail;

        Cell(AuthzMatrixSpec.Persona persona, AuthzMatrixSpec.Row row, String expected, AuthzMatrixSpec.Rule rule,
                boolean critical) {
            this.persona = persona;
            this.row = row;
            this.expected = expected;
            this.rule = rule;
            this.critical = critical;
        }

        /** match, mismatch or error. */
        String status() {
            if (actual.equals("!")) {
                return "error";
            }
            return actual.equals(expected) || actual.equals("-") && expected.equals("N") ? "match" : "mismatch";
        }
    }

    @TestFactory
    Stream<DynamicNode> guiAuthorizations(MidPointConfigTester t) {
        AuthzMatrixSpec spec = AuthzMatrixSpec.read(specFile(), t.line());
        Map<String, String> users = check(spec, t);
        spec.warnings.forEach(warning -> System.err.println("Tester: " + warning + " (on " + t.line() + ")"));
        List<Cell> cells = evaluate(spec, t, users);
        List<Coverage> coverage = coverage(spec, t, users);
        List<String> warnings = new ArrayList<>(spec.warnings);
        for (Coverage c : coverage) {
            if (c.warning != null) {
                System.err.println("Tester: " + c.warning + " (on " + t.line() + ")");
                warnings.add(c.warning);
            }
        }
        Map<String, String> authorizations = new HashMap<>();
        for (AuthzMatrixSpec.Persona p : spec.personas.values()) {
            StringBuilder text = new StringBuilder(t.actor(users.get(p.id)).authorizations());
            List<Coverage> own = new ArrayList<>();
            coverage.stream().filter(c -> c.persona.equals(p.id)).forEach(own::add);
            if (!own.isEmpty()) {
                text.append("\nThe samples of their selectors (inside / outside):");
                own.forEach(c -> text.append("\n  ").append(c));
            }
            authorizations.put(p.id, text.toString());
        }
        Path report = AuthzMatrixReport.write(spec, t.line(), cells, authorizations, warnings,
                TesterRuntime.get().workDir().resolve("authz-matrix"));
        System.out.println("Tester: authorization matrix " + spec.file + " on " + t.line() + ": "
                + summary(cells) + ", " + report.toUri());

        List<DynamicNode> out = new ArrayList<>();
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            List<DynamicNode> tests = new ArrayList<>();
            for (Cell cell : cells) {
                if (cell.persona == persona) {
                    tests.add(DynamicTest.dynamicTest(cell.row.path, () -> verify(cell, authorizations, report)));
                }
            }
            out.add(DynamicContainer.dynamicContainer(persona.label, tests));
        }
        return out.stream();
    }

    static void verify(Cell cell, Map<String, String> authorizations, Path report) {
        String status = cell.status();
        if (status.equals("match")) {
            return;
        }
        StringBuilder sb = new StringBuilder(cell.persona.label + " / " + cell.row.path + " (" + cell.row.check
                + "): ");
        sb.append(status.equals("error") ? "the check failed: " + cell.detail
                : "expected " + cell.expected + " but was " + cell.actual);
        sb.append("\n  expected by ").append(cell.rule == null ? "the default" : "rule '" + cell.rule.name + "'")
                .append(" (").append(cell.rule == null ? "default" : "line " + cell.rule.line).append(')');
        if (cell.rule != null && cell.rule.note != null) {
            sb.append("\n  the rule's note: ").append(cell.rule.note);
        }
        if (cell.detail != null && !status.equals("error")) {
            sb.append("\n  ").append(cell.detail);
        }
        sb.append("\n").append(authorizations.get(cell.persona.id));
        sb.append("\nThe matrix: ").append(report.toUri());
        throw new AssertionError(sb.toString());
    }

    private static String summary(List<Cell> cells) {
        Map<String, Integer> counts = new HashMap<>();
        cells.forEach(cell -> counts.merge(cell.status(), 1, Integer::sum));
        return cells.size() + " cells, " + counts.getOrDefault("match", 0) + " as expected, "
                + counts.getOrDefault("mismatch", 0) + " not, " + counts.getOrDefault("error", 0) + " failed";
    }

    // ---- the samples of the authorizations' selectors -----------------------------------------------------------

    /**
     * The clauses of a selector midPoint turns into a search filter on every line; the others (owner, delegator,
     * assignee, tenant...) are searched by some lines or repositories only, or by none: such a selector is not
     * checked.
     */
    private static final Set<String> SEARCHABLE = Set.of("type", "filter", "orgRef", "orgRelation", "roleRelation",
            "special", "archetypeRef", "allowInactive", "name", "description", "documentation");
    /** The clauses that select nothing more than the type. */
    private static final Set<String> TYPE_ONLY = Set.of("type", "allowInactive", "name", "description",
            "documentation");
    /** The types a sample object may have ({@code objects:} and the persona's own user). */
    private static final List<String> SAMPLE_TYPES = List.of("UserType", "RoleType", "OrgType", "ServiceType",
            "ArchetypeType");

    /**
     * How the samples cover a selector of a persona's authorization ({@code object} or {@code target}): the samples
     * it selects for the persona and those it does not. Without both, the matrix does not show where the selector
     * ends: a warning.
     */
    static final class Coverage {
        final String persona;
        final String authorization;
        final String side;
        final String selector;
        final List<String> inside = new ArrayList<>();
        final List<String> outside = new ArrayList<>();
        /** The clauses the tester does not check by a search: not checked; null when checked. */
        String unchecked;
        String warning;

        Coverage(String persona, String authorization, String side, String selector) {
            this.persona = persona;
            this.authorization = authorization;
            this.side = side;
            this.selector = selector;
        }

        @Override
        public String toString() {
            String head = authorization + " " + side + " " + selector + ": ";
            if (unchecked != null) {
                return head + "not checked (" + unchecked + ")";
            }
            return head + (inside.isEmpty() ? "-" : String.join(", ", inside)) + " / "
                    + (outside.isEmpty() ? "-" : String.join(", ", outside)) + (warning != null ? " (!)" : "");
        }
    }

    /**
     * For each persona, each selector of their authorizations as midPoint compiled them at login (from every role,
     * org and archetype, assigned or induced) that selects more narrowly than a type
     * (a filter, {@code special: self}, {@code orgRelation}...), and of a type the samples may have: the samples it
     * selects and does not, as midPoint selects them for the persona ({@code Access.selectedBy}); the persona's own
     * user is a sample ({@code self}). A warning when no sample is inside or none outside.
     */
    static List<Coverage> coverage(AuthzMatrixSpec spec, MidPointConfigTester t, Map<String, String> users) {
        MidPoint mp = t.midpoint();
        List<Coverage> out = new ArrayList<>();
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            String name = users.get(persona.id);
            UserType user = mp.user(name);
            Map<String, ObjectType> samples = new java.util.LinkedHashMap<>();
            samples.put("self", user);
            for (AuthzMatrixSpec.SampleObject object : spec.objects.values()) {
                samples.put(object.id, mp.find(type(object.type), object.name));
            }
            for (CompiledAuthorization compiled : t.actor(name).compiledAuthorizations()) {
                com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType authorization =
                        compiled.authorization();
                String label = "'" + (authorization.getName() != null ? authorization.getName() : "unnamed")
                        + "' (" + Actor.source(compiled.source()) + ")";
                for (String side : List.of("object", "target")) {
                    for (com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector
                            : side.equals("object") ? authorization.getObject() : authorization.getTarget()) {
                        Coverage c = cover(persona, label, side, selector, samples, mp, name);
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Coverage cover(AuthzMatrixSpec.Persona persona, String label, String side,
            com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector,
            Map<String, ObjectType> samples, MidPoint mp, String user) {
        Set<String> clauses = new java.util.TreeSet<>();
        for (Object item : selector.asPrismContainerValue().getItems()) {
            clauses.add(((com.evolveum.midpoint.prism.Item<?, ?>) item).getElementName().getLocalPart());
        }
        if (TYPE_ONLY.containsAll(clauses)) {
            return null;
        }
        Class<? extends ObjectType> selected = selector.getType() == null ? ObjectType.class
                : mp.prismContext().getSchemaRegistry().determineCompileTimeClass(selector.getType());
        if (selected == null || SAMPLE_TYPES.stream().noneMatch(t -> selected.isAssignableFrom(type(t)))) {
            // shadows, tasks, cases: no sample can be of their type
            return null;
        }
        Coverage c = new Coverage(persona.id, label, side, Actor.selector(selector));
        Set<String> unchecked = new java.util.TreeSet<>(clauses);
        unchecked.removeAll(SEARCHABLE);
        if (!unchecked.isEmpty()) {
            c.unchecked = String.join(", ", unchecked) + ": not checked by a search";
            return c;
        }
        Map<Class<? extends ObjectType>, List<String>> byType = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ObjectType> sample : samples.entrySet()) {
            if (selected.isAssignableFrom(sample.getValue().getClass())) {
                byType.computeIfAbsent(sample.getValue().getClass(), k -> new ArrayList<>()).add(sample.getKey());
            }
        }
        String where = "persona " + persona.id + ": " + label + " " + side + " " + c.selector;
        if (byType.isEmpty()) {
            c.warning = where + ": no sample object of its type; add one inside it and one outside";
            return c;
        }
        for (Map.Entry<Class<? extends ObjectType>, List<String>> entry : byType.entrySet()) {
            List<String> oids = new ArrayList<>();
            entry.getValue().forEach(id -> oids.add(samples.get(id).getOid()));
            Set<String> in = mp.actor(user).selectedBy(selector, entry.getKey(), oids).value();
            for (String id : entry.getValue()) {
                (in.contains(samples.get(id).getOid()) ? c.inside : c.outside).add(id);
            }
        }
        if (c.inside.isEmpty()) {
            c.warning = where + ": no sample inside it (" + String.join(", ", c.outside) + " outside); add an"
                    + " object it selects";
        } else if (c.outside.isEmpty()) {
            c.warning = where + ": no sample outside it (" + String.join(", ", c.inside) + " inside); add an object"
                    + " it does not select";
        }
        return c;
    }

    // ---- the names against the deployment, the personas' users ------------------------------------------------

    /**
     * Checks the names, creates the personas' users, adds the auto rows (the GUI's pages, the items, the views,
     * features and menu links of the personas' GUI profiles), checks the rows; the users' names by persona.
     */
    static Map<String, String> check(AuthzMatrixSpec spec, MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            if (persona.user != null && mp.user(persona.user) == null) {
                throw spec.error(persona.line, "persona " + persona.id + ": no user " + persona.user);
            }
            for (String role : persona.roles) {
                if (mp.find(AbstractRoleType.class, role) == null) {
                    throw spec.error(persona.line, "persona " + persona.id + ": no role " + role);
                }
            }
            for (AuthzMatrixSpec.Membership org : persona.orgs) {
                if (mp.find(OrgType.class, org.org) == null) {
                    throw spec.error(persona.line, "persona " + persona.id + ": no org " + org.org);
                }
                relation(org.relation, spec, persona.line, mp);
            }
            if (persona.archetype != null && mp.find(ArchetypeType.class, persona.archetype) == null) {
                throw spec.error(persona.line, "persona " + persona.id + ": no archetype " + persona.archetype);
            }
        }
        for (AuthzMatrixSpec.SampleObject object : spec.objects.values()) {
            if (mp.find(type(object.type), object.name) == null) {
                throw spec.error(object.line, "object " + object.id + ": no " + object.type + " " + object.name);
            }
        }
        Map<String, String> users = new HashMap<>();
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            users.put(persona.id, persona.user != null ? persona.user : create(mp, persona));
        }
        spec.expand(expander(t, spec, users));
        Set<String> guiAll = guiAll(t.line());
        for (AuthzMatrixSpec.Row row : spec.checks()) {
            if (row.kind == AuthzMatrixSpec.Kind.OPEN) {
                String url = url(row.argument, spec, t, "00000000-0000-0000-0000-000000000000");
                if (GuiPages.of().required(url, guiAll).matches().isEmpty()) {
                    throw spec.error(row.line, "no page of the GUI matches " + row.argument);
                }
            }
            if (row.kind == AuthzMatrixSpec.Kind.ASSIGN || row.kind == AuthzMatrixSpec.Kind.UNASSIGN) {
                relation(row.relation, spec, row.line, mp);
            }
            if (row.kind == AuthzMatrixSpec.Kind.READ_ITEM || row.kind == AuthzMatrixSpec.Kind.MODIFY_ITEM) {
                String type = row.object.equals("self") ? "UserType" : spec.objects.get(row.object).type;
                PrismObjectDefinition<?> definition = mp.prismContext().getSchemaRegistry()
                        .findObjectDefinitionByCompileTimeClass(type(type));
                if (definition.findItemDefinition(path(row.argument)) == null) {
                    throw spec.error(row.line, "no item " + row.argument + " in " + type);
                }
            }
        }
        return users;
    }

    /**
     * The persona's user: added (the deployment's user template applies, as to any new user), then assigned the
     * archetype, orgs and roles it does not have yet (a template that gives everyone End user gives it here too).
     */
    private static String create(MidPoint mp, AuthzMatrixSpec.Persona persona) {
        String name = "authz-" + persona.id;
        // as XML: a bean built here has no prism context on 4.0
        com.github.wadahiro.midpoint.tester.Run run = mp.addObjectXml(
                "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\"><name>" + xml(name)
                        + "</name><fullName>" + xml(persona.label) + "</fullName></user>");
        if (run.failure() != null) {
            throw new IllegalStateException("persona " + persona.id + ": adding " + name + " failed: "
                    + run.failure().getMessage(), run.failure());
        }
        List<AuthzMatrixSpec.Membership> targets = new ArrayList<>();
        if (persona.archetype != null) {
            targets.add(new AuthzMatrixSpec.Membership(persona.archetype, null));
        }
        targets.addAll(persona.orgs);
        persona.roles.forEach(role -> targets.add(new AuthzMatrixSpec.Membership(role, null)));
        for (AuthzMatrixSpec.Membership target : targets) {
            ObjectType object = mp.find(AbstractRoleType.class, target.org);
            QName relation = relation(target.relation, null, 0, mp);
            boolean assigned = false;
            for (AssignmentType assignment : mp.user(name).getAssignment()) {
                assigned |= assignment.getTargetRef() != null
                        && object.getOid().equals(assignment.getTargetRef().getOid())
                        && localPart(assignment.getTargetRef().getRelation()).equals(localPart(relation));
            }
            if (!assigned) {
                com.github.wadahiro.midpoint.tester.Run assignment = relation == null
                        ? mp.assign(UserType.class, name, object.getOid())
                        : mp.assign(UserType.class, name, object.getOid(), relation);
                if (assignment.failure() != null) {
                    throw new IllegalStateException("persona " + persona.id + ": assigning " + target + " failed: "
                            + assignment.failure().getMessage(), assignment.failure());
                }
            }
        }
        return name;
    }

    // ---- the cells --------------------------------------------------------------------------------------------

    private static List<Cell> evaluate(AuthzMatrixSpec spec, MidPointConfigTester t, Map<String, String> users) {
        List<Cell> cells = new ArrayList<>();
        Map<String, GuiProfile> profiles = new HashMap<>();
        for (AuthzMatrixSpec.Persona persona : spec.personas.values()) {
            Actor actor = t.actor(users.get(persona.id));
            String self = t.midpoint().user(users.get(persona.id)).getOid();
            Map<String, List<ObjectRef>> searches = new HashMap<>();
            Map<String, Boolean> gets = new HashMap<>();
            Map<String, Form> forms = new HashMap<>();
            for (AuthzMatrixSpec.Row row : spec.checks()) {
                Object[] expected = spec.expected(persona.id, row);
                Cell cell = new Cell(persona, row, (String) expected[0], (AuthzMatrixSpec.Rule) expected[1],
                        spec.critical(row));
                try {
                    ObjectRef sample = row.object == null ? null
                            : object(spec, t, row.object, users.get(persona.id));
                    switch (row.kind) {
                        case OPEN:
                            String url = url(row.argument, spec, t, self);
                            cell.detail = "opens " + url;
                            cell.actual = yn(actor.canOpen(url));
                            break;
                        case SEARCH:
                            cell.actual = yn(searches.computeIfAbsent(sample.type(),
                                    k -> actor.search(type(k))).contains(sample));
                            break;
                        case GET:
                            cell.actual = yn(gets.computeIfAbsent(sample.type() + "/" + sample.name(),
                                    k -> actor.canGet(sample)));
                            break;
                        case ADD:
                        case MODIFY:
                        case DELETE:
                            cell.actual = yn(actor.isAuthorized(MODEL + row.kind.name().toLowerCase(Locale.ROOT),
                                    sample));
                            break;
                        case ASSIGN:
                        case UNASSIGN:
                            ObjectRef target = object(spec, t, row.argument, users.get(persona.id));
                            QName relation = relation(row.relation, null, 0, t.midpoint());
                            cell.detail = (row.kind == AuthzMatrixSpec.Kind.ASSIGN ? "assigns " : "unassigns ") + target
                                    + (relation == null ? "" : " as " + relation.getLocalPart())
                                    + (row.kind == AuthzMatrixSpec.Kind.ASSIGN ? " to " : " from ") + sample;
                            cell.actual = yn(row.kind == AuthzMatrixSpec.Kind.ASSIGN
                                    ? actor.canAssign(sample, target, relation)
                                    : actor.canUnassign(sample, target, relation));
                            break;
                        case READ:
                            boolean found = searches.computeIfAbsent(sample.type(), k -> actor.search(type(k)))
                                    .contains(sample);
                            cell.actual = yn(found && gets.computeIfAbsent(sample.type() + "/" + sample.name(),
                                    k -> actor.canGet(sample)));
                            break;
                        case VIEW:
                        case FEATURE:
                        case MENU_LINK:
                            gui(cell, row, actor, profiles.computeIfAbsent(persona.id, p -> actor.guiProfile()));
                            break;
                        default:
                            if (!gets.computeIfAbsent(sample.type() + "/" + sample.name(), k -> actor.canGet(sample))) {
                                cell.actual = "-";
                                cell.detail = "not applicable: the persona does not get " + sample;
                                break;
                            }
                            Form form = forms.computeIfAbsent(sample.type() + "/" + sample.name(),
                                    k -> actor.form(sample));
                            cell.actual = yn(row.kind == AuthzMatrixSpec.Kind.READ_ITEM ? form.canRead(row.argument)
                                    : form.canModify(row.argument));
                    }
                } catch (RuntimeException e) {
                    cell.actual = "!";
                    cell.detail = e.toString();
                }
                cells.add(cell);
            }
        }
        return cells;
    }

    /**
     * The side menu's authorization of a type's collection views (LeftMenuAuthzUtil.viewsAuthorizationMaps, 4.4+;
     * 4.0 PageBase the same), with {@code #guiAll}: the types the matrix has view rows of.
     */
    static final Map<String, List<String>> VIEW_ACTIONS = Map.of(
            "UserType", List.of(UI + "usersAll", UI + "usersView"),
            "OrgType", List.of(UI + "orgAll", UI + "orgsView"),
            "RoleType", List.of(UI + "rolesAll", UI + "rolesView"),
            "ServiceType", List.of(UI + "servicesAll", UI + "servicesView"),
            "ResourceType", List.of(UI + "resourcesAll", UI + "resourcesView"),
            "CaseType", List.of(UI + "casesAll", UI + "casesView"),
            "TaskType", List.of(UI + "tasksAll", UI + "tasksView"));

    /**
     * A cell of the persona's GUI profile. A view: its profile has it, the line's menu lists it ({@code inMenu}),
     * and the persona is allowed its type's view actions (as a page: one allowed, none denied). A feature: midPoint's
     * {@code isFeatureVisible}. A menu link: its profile has it.
     */
    private static void gui(Cell cell, AuthzMatrixSpec.Row row, Actor actor, GuiProfile profile) {
        switch (row.kind) {
            case VIEW:
                String[] view = row.argument.split("/", 2);
                GuiProfile.CollectionView found = profile.view(
                        new QName(com.evolveum.midpoint.schema.constants.SchemaConstants.NS_C, view[0]), view[1]);
                List<String> actions = VIEW_ACTIONS.get(view[0]);
                if (found == null) {
                    cell.actual = "N";
                    cell.detail = "not in the persona's GUI profile";
                } else if (!found.inMenu()) {
                    cell.actual = "N";
                    cell.detail = "in the GUI profile, not listed by the line's menu"
                            + (found.visibility() == null ? "" : " (" + found.visibility().value() + ")");
                } else if (actions == null) {
                    cell.actual = "!";
                    cell.detail = "the menu's authorization of " + view[0] + " views is not modeled";
                } else {
                    cell.actual = yn(actor.canOpen(actions.toArray(new String[0])));
                    cell.detail = "in the menu if allowed any of " + actions;
                }
                break;
            case FEATURE:
                cell.actual = yn(profile.isFeatureVisible(row.argument));
                cell.detail = "visibility " + (profile.featureVisibility(row.argument) == null ? "not configured"
                        : profile.featureVisibility(row.argument).value());
                break;
            default:
                cell.actual = yn(profile.additionalMenuLinks().stream()
                        .anyMatch(link -> row.argument.equals(link.getLabel())));
        }
    }

    private static ObjectRef object(AuthzMatrixSpec spec, MidPointConfigTester t, String id, String self) {
        if (id.equals("self")) {
            UserType user = t.midpoint().user(self);
            return ObjectRef.of("UserType", self, user.getOid());
        }
        AuthzMatrixSpec.SampleObject sample = spec.objects.get(id);
        ObjectType object = t.midpoint().find(type(sample.type), sample.name);
        return ObjectRef.of(sample.type, sample.name, object.getOid());
    }

    /** The URL with {@code {object}} replaced by the object's OID, {@code {self}} by the persona's. */
    private static String url(String url, AuthzMatrixSpec spec, MidPointConfigTester t, String selfOid) {
        String out = url.replace("{self}", selfOid);
        for (AuthzMatrixSpec.SampleObject object : spec.objects.values()) {
            if (out.contains("{" + object.id + "}")) {
                out = out.replace("{" + object.id + "}",
                        t.midpoint().find(type(object.type), object.name).getOid());
            }
        }
        if (out.matches(".*\\{[^}]*}.*")) {
            throw new IllegalArgumentException("unknown object in " + url + "; objects: " + spec.objects.keySet());
        }
        return out;
    }

    /**
     * A relation as written: {@code manager} (midPoint's {@code org:} relations), {@code org:manager}, or
     * {@code {namespace}name}; null for the default. Unknown to midPoint's relation registry: an error at the line.
     */
    private static QName relation(String relation, AuthzMatrixSpec spec, int line, MidPoint mp) {
        if (relation == null) {
            return null;
        }
        QName out = relation.startsWith("{") ? QName.valueOf(relation)
                : new QName(ORG, relation.startsWith("org:") ? relation.substring(4) : relation);
        if (spec != null && mp.bean(RelationRegistry.class).getRelationDefinition(out) == null) {
            throw spec.error(line, "no relation " + relation + " in midPoint's relation registry");
        }
        return out;
    }

    private static String localPart(QName relation) {
        return relation == null ? "default" : relation.getLocalPart();
    }

    /** The GUI's pages of the line, the items of a type in its schema, what the personas' GUI profiles have. */
    private static AuthzMatrixSpec.Expander expander(MidPointConfigTester t, AuthzMatrixSpec spec, Map<String, String> users) {
        List<GuiProfile> profiles = new ArrayList<>();
        spec.personas.values().forEach(p -> profiles.add(t.actor(users.get(p.id)).guiProfile()));
        return new AuthzMatrixSpec.Expander() {
            @Override
            public List<String[]> views() {
                Map<String, String[]> out = new java.util.LinkedHashMap<>();
                for (GuiProfile profile : profiles) {
                    for (GuiProfile.CollectionView view : profile.views()) {
                        String type = view.type().getLocalPart();
                        if (VIEW_ACTIONS.containsKey(type)) {
                            out.putIfAbsent(type + "/" + view.identifier(),
                                    new String[] {type, view.identifier(), view.label()});
                        } else {
                            spec.warnings.add("view " + type + "/" + view.identifier() + ": no row, the menu's"
                                    + " authorization of " + type + " views is not modeled");
                        }
                    }
                }
                return new ArrayList<>(out.values());
            }

            @Override
            public List<String> features() {
                Set<String> out = new LinkedHashSet<>();
                profiles.forEach(p -> p.features().forEach(f -> out.add(f.getIdentifier())));
                return new ArrayList<>(out);
            }

            @Override
            public List<String> menuLinks() {
                Set<String> out = new LinkedHashSet<>();
                profiles.forEach(p -> p.additionalMenuLinks().forEach(l -> out.add(l.getLabel())));
                return new ArrayList<>(out);
            }

            @Override
            public List<String[]> pages() {
                return GuiPages.of().pages();
            }

            @Override
            public List<String> items(String type) {
                List<String> out = new ArrayList<>();
                for (Object item : t.midpoint().prismContext().getSchemaRegistry()
                        .findObjectDefinitionByCompileTimeClass(type(type)).getDefinitions()) {
                    out.add(((com.evolveum.midpoint.prism.ItemDefinition<?>) item).getItemName().getLocalPart());
                }
                return out;
            }
        };
    }

    private static Set<String> guiAll(String line) {
        Set<String> out = new LinkedHashSet<>();
        out.add(AuthorizationConstants.AUTZ_GUI_ALL_URL);
        if (line.equals("4.0")) {
            out.add("http://midpoint.evolveum.com/xml/ns/public/security/authorization-3#guiAll");
        }
        return out;
    }

    private static ItemPath path(String path) {
        List<Object> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            segments.add(new QName(segment));
        }
        return ItemPath.create(segments.toArray());
    }

    @SuppressWarnings("unchecked")
    private static Class<ObjectType> type(String type) {
        try {
            return (Class<ObjectType>) Class.forName("com.evolveum.midpoint.xml.ns._public.common.common_3." + type);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("No midPoint type " + type, e);
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    private Path specFile() {
        AuthzMatrix annotation = getClass().getAnnotation(AuthzMatrix.class);
        if (annotation == null) {
            throw new IllegalStateException(getClass().getName() + " needs @AuthzMatrix(\"classpath:...\")");
        }
        String value = annotation.value();
        if (!value.startsWith("classpath:")) {
            return Path.of(value);
        }
        String resource = value.substring("classpath:".length());
        URL url = getClass().getClassLoader().getResource(resource.startsWith("/") ? resource.substring(1) : resource);
        if (url == null) {
            throw new IllegalStateException("No authorization matrix " + value + " on the test classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Bad URL " + url, e);
        }
    }
}
