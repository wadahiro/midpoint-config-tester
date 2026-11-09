package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialArchetypes;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialCollections;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialDashboards;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialMarks;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialOrgs;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialPolicies;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialReports;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialRoles;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialServices;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialTasks;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialTemplates;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialUsers;
import com.github.wadahiro.midpoint.configtester.names.midpoint.UserItems;
import com.github.wadahiro.midpoint.tester.internal.TesterRuntime;

/**
 * The typed names of midPoint's own objects and items ({@code configtester.names.midpoint}) on the running line: each
 * initial object name of the line is in the repository after the line's initial import (by OID, with that name), each
 * initial object of those types the line ships has a name for the line, and each user item exists in the line's
 * schema exactly on the lines its name says. A new line or patch release that changes them fails here: run
 * {@code .claude/skills/add-midpoint-line/scripts/initial-names.py} again.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class InitialNamesTest {

    static final List<Class<?>> INITIAL = List.of(InitialUsers.class, InitialRoles.class, InitialArchetypes.class,
            InitialOrgs.class, InitialServices.class, InitialTasks.class, InitialReports.class,
            InitialCollections.class, InitialDashboards.class, InitialPolicies.class, InitialMarks.class,
            InitialTemplates.class);
    /** The type elements of the initial object files that have names, as the script maps them. */
    static final Map<String, String> TYPES = Map.ofEntries(Map.entry("user", "UserType"),
            Map.entry("role", "RoleType"), Map.entry("archetype", "ArchetypeType"), Map.entry("org", "OrgType"),
            Map.entry("service", "ServiceType"), Map.entry("task", "TaskType"), Map.entry("report", "ReportType"),
            Map.entry("objectCollection", "ObjectCollectionType"), Map.entry("dashboard", "DashboardType"),
            Map.entry("securityPolicy", "SecurityPolicyType"), Map.entry("valuePolicy", "ValuePolicyType"),
            Map.entry("policy", "PolicyType"), Map.entry("mark", "MarkType"),
            Map.entry("objectTemplate", "ObjectTemplateType"));

    @Test
    void theInitialNamesOfTheLineAreInTheRepository(MidPointConfigTester t) throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (ObjectRef ref : constants(INITIAL, ObjectRef.class)) {
            if (!ref.existsOn(t.line())) {
                continue;
            }
            checked++;
            @SuppressWarnings("unchecked")
            Class<? extends ObjectType> type = (Class<? extends ObjectType>) Class.forName(
                    "com.evolveum.midpoint.xml.ns._public.common.common_3." + ref.type());
            ObjectType object = t.midpoint().object(type, ref.oid());
            if (object == null) {
                problems.add(ref + ": no " + ref.type() + " " + ref.oid());
            } else if (!ref.name().equals(object.getName().getOrig())) {
                problems.add(ref + ": named " + object.getName().getOrig());
            }
        }
        assertThat(problems).as("initial names of midPoint " + t.line()).isEmpty();
        assertThat(checked).isPositive();
    }

    @Test
    void everyInitialObjectOfTheLineHasAName(MidPointConfigTester t) throws Exception {
        Set<String> named = new TreeSet<>();
        for (ObjectRef ref : constants(INITIAL, ObjectRef.class)) {
            if (ref.existsOn(t.line())) {
                named.add(ref.type() + " " + ref.oid() + " " + ref.name());
            }
        }
        Set<String> shipped = new TreeSet<>();
        for (String[] object : TesterRuntime.initialObjectsOfTheLine()) {
            String type = TYPES.get(object[0]);
            // 4.0's reports are Jasper reports, which the tester does not import
            if (type != null && !("ReportType".equals(type) && "4.0".equals(t.line()))) {
                shipped.add(type + " " + object[1] + " " + object[2]);
            }
        }
        // one name of 4.4 is left out (two typos that 4.8 fixed)
        shipped.removeIf(o -> o.endsWith("(backgroud processing for chnage execution)"));
        assertThat(named).as("typed initial names of midPoint " + t.line()).isEqualTo(shipped);
    }

    @Test
    void theUserItemsAreInTheSchemaOfTheirLines(MidPointConfigTester t) throws Exception {
        PrismObjectDefinition<UserType> user = t.midpoint().bean(PrismContext.class).getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(UserType.class);
        List<String> problems = new ArrayList<>();
        for (ItemRef item : constants(List.of(UserItems.class), ItemRef.class)) {
            Object[] segments = java.util.Arrays.stream(item.path().split("/"))
                    .map(segment -> new QName(SchemaConstants.NS_C, segment)).toArray();
            boolean defined = user.findItemDefinition(ItemPath.create(segments)) != null;
            if (defined != item.existsOn(t.line())) {
                problems.add(item.path() + (defined ? " is defined (" + item.lines() + ")" : " is not defined"));
            }
        }
        assertThat(problems).as("user items of midPoint " + t.line()).isEmpty();
    }

    /** A typed name works in the verbs as the string does. */
    @Test
    void theInitialNamesWorkInTheVerbs(MidPointConfigTester t) {
        t.user("jack").hasRole(InitialRoles.END_USER).hasNoRole(InitialRoles.SUPERUSER)
                .hasItem(UserItems.FULL_NAME, "Jack Sparrow");
        assertThat(t.user("jack").values(UserItems.NAME)).containsExactly("jack");
        assertThat(t.task(InitialTasks.VALIDITY_SCANNER).name()).isEqualTo("Validity Scanner");
    }

    static <T> List<T> constants(List<Class<?>> classes, Class<T> type) throws IllegalAccessException {
        List<T> constants = new ArrayList<>();
        for (Class<?> c : classes) {
            for (Field field : c.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                    constants.add(type.cast(field.get(null)));
                }
            }
        }
        return constants.stream().collect(Collectors.toList());
    }
}
