package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.tester.internal.GoldenComparison;
import com.github.wadahiro.midpoint.tester.internal.GoldenComparison.Difference;
import com.github.wadahiro.midpoint.tester.internal.GoldenComparison.Kind;
import com.github.wadahiro.midpoint.tester.internal.GoldenResults;

/**
 * Golden results: what is recorded per place, and what verification reports when the configuration changes
 * (template-v1.xml recorded, template-v2.xml verified). The record / verify round trip of the whole suite runs in CI
 * ({@code golden.mode}).
 */
@MidPointTest
class GoldenTest {

    static final File DIR = Fixtures.dir("golden");
    static final String JDOE = "<user " + Fixtures.NS + "><name>jdoe</name><givenName>John</givenName>"
            + "<familyName>Doe</familyName></user>";
    static final String FULL_NAME = "objectTemplate:Golden template/mapping[fullName]";
    static final String POLICY_RULE = "systemConfiguration:SystemConfiguration/globalPolicyRule[family-name-check]"
            + "/policyConstraints/objectState[family-name-state]/expression";

    @BeforeAll
    static void configure(MidPoint mp) {
        mp.importObject(new File(DIR, "template-v1.xml"));
        mp.importObject(new File(DIR, "system-configuration.xml"));
    }

    @Test
    void recordedPerPlace(MidPoint mp) {
        GoldenResults.TestResult result = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));

        GoldenResults.Place fullName = result.runs.get(1).places.get(FULL_NAME);
        // the final outcome (the last evaluation for jdoe) is compared; the earlier ones are kept apart: the template
        // also runs for the old state, where the sources are empty
        if (Lines.atLeast(mp, "4.4")) {
            assertThat(fullName.outputs).containsExactly("condition true->true new=[\"John Doe\"]");
            assertThat(fullName.intermediate).contains(
                    "condition true->true new=[\"John Doe\"] removed=[\"null null\"] | old=[\"null null\"]");
        } else {
            // 4.0: the last evaluation of an added user still has the old state's (empty) result
            assertThat(fullName.outputs).containsExactly(
                    "condition true->true new=[\"John Doe\"] removed=[\"null null\"] | old=[\"null null\"]");
        }
        assertThat(fullName.count).isGreaterThan(1);
        assertThat(fullName.inputs).contains("src givenName = [] -> [\"John\"]")
                .anySatisfy(input -> assertThat(input).startsWith("vars ").contains("focus").contains("operation"));
        // a mapping's expression is not a place of its own: the mapping has its outcome
        assertThat(result.runs.get(1).places).doesNotContainKey(FULL_NAME + "/expression");
        assertThat(result.runs.get(1).operation).startsWith("add ");
        assertThat(result.runs.get(1).key).isEqualTo(result.runs.get(1).operation);

        // the text form reads back as written
        GoldenResults file = new GoldenResults("4.8");
        file.put(result);
        String text = file.write("GoldenTest");
        assertThat(GoldenResults.parse(text).write("GoldenTest")).isEqualTo(text);
        assertThat(text).contains("\n    place " + FULL_NAME + "\n      count ");
    }

    @Test
    void sameRunsHaveNoDifferences(MidPoint mp) {
        GoldenResults.TestResult first = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));
        mp.deleteObject(UserType.class, "jdoe");
        GoldenResults.TestResult second = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));

        // the generated OIDs differ, their normalized names do not
        assertThat(GoldenComparison.compare(first, second)).isEmpty();
    }

    @Test
    void changedConfigurationGivesDifferences(MidPoint mp) {
        GoldenResults.TestResult golden = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));
        mp.deleteObject(UserType.class, "jdoe");
        mp.importObject(new File(DIR, "template-v2.xml"));
        GoldenResults.TestResult actual = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));

        List<Difference> differences = GoldenComparison.compare(golden, actual);

        assertThat(differences).extracting(d -> d.kind + " " + d.identity).containsExactlyInAnyOrder(
                "CHANGED " + FULL_NAME,
                "NEW objectTemplate:Golden template/mapping[locality]",
                "MISSING objectTemplate:Golden template/mapping[nickName]",
                "INPUTS objectTemplate:Golden template/mapping[title]");
        Difference changed = differences.stream().filter(d -> d.kind == Kind.CHANGED).findFirst().orElseThrow();
        // 4.0: the last evaluation of an added user still has the old state's (empty) result
        String goldenOut = Lines.atLeast(mp, "4.4") ? "condition true->true new=[\"John Doe\"]"
                : "condition true->true new=[\"John Doe\"] removed=[\"null null\"] | old=[\"null null\"]";
        assertThat(changed.golden).containsExactly(goldenOut);
        assertThat(changed.actual).containsExactly(Lines.atLeast(mp, "4.4") ? "condition true->true new=[\"JOHN DOE\"]"
                : "condition true->true new=[\"JOHN DOE\"] removed=[\"NULL NULL\"] | old=[\"NULL NULL\"]");
        Difference inputs = differences.stream().filter(d -> d.kind == Kind.INPUTS).findFirst().orElseThrow();
        assertThat(inputs.actual).contains("src familyName = [] -> [\"Doe\"]");

        assertThat(GoldenComparison.fails(differences, GoldenComparison.DEFAULT_FAILING)).isTrue();
        assertThat(GoldenComparison.fails(differences, GoldenComparison.parseKinds("count"))).isFalse();
        String report = GoldenComparison.markdown("GoldenTest.t", differences, GoldenComparison.DEFAULT_FAILING);
        assertThat(report).startsWith("### GoldenTest.t - FAILED (3 failing, 1 notes)\n")
                .contains("\nRun 1: `add user:")
                .contains("- **changed** `" + FULL_NAME + "`\n"
                        + "  - golden: `" + goldenOut + "`\n")
                .contains("- **missing** `objectTemplate:Golden template/mapping[nickName]`\n"
                        + "  - golden: `condition true->true new=[\"john\"]" + (Lines.atLeast(mp, "4.4") ? "" : " | old=[]")
                        + "`\n")
                .contains("- **new** `objectTemplate:Golden template/mapping[locality]`\n")
                .contains("- note: inputs `objectTemplate:Golden template/mapping[title]`\n");
        // failing differences before notes
        assertThat(report.indexOf("**new**")).isLessThan(report.indexOf("note: inputs"));
    }

    @Test
    void errorsAreNormalized(MidPoint mp) {
        mp.importObject(new File(Fixtures.ROOT, "object-template-user-broken.xml"));
        mp.importObject(new File(Fixtures.ROOT, "system-configuration.xml"));

        Run run = mp.attempt(() -> mp.addObjectXml("<user " + Fixtures.NS + "><name>bob</name>"
                + "<costCenter>IT</costCenter></user>"));

        assertThat(run.isSuccess()).isFalse();
        GoldenResults.Place broken = GoldenResults.of("t", List.of(run)).runs.get(1).places
                .get("objectTemplate:User template/mapping[employee-number]");
        // the root cause's simple class name and its message
        // (4.0 fails in the evaluation for the old state of the added user, see RecorderTest.mappingWithError)
        assertThat(broken.outputs).containsExactly("condition true->true error IllegalStateException: no number for "
                + (Lines.atLeast(mp, "4.4") ? "IT" : "null"));
    }

    @Test
    void ownSystemConfigurationIsComparedBaselineArchetypeIsANote(MidPoint mp) {
        // the test's own system configuration has the OID of the baseline's: its places are tested configuration
        mp.importObject(new File(DIR, "system-configuration-policy-v1.xml"));
        GoldenResults.TestResult golden = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));
        GoldenResults.Place rule = golden.runs.get(1).places.get(POLICY_RULE);
        assertThat(rule.flags).doesNotContain("baseline");
        assertThat(rule.outputs).containsExactly("new=[false]");
        mp.deleteObject(UserType.class, "jdoe");
        mp.importObject(new File(DIR, "system-configuration-policy-v2.xml"));
        GoldenResults.TestResult actual = GoldenResults.of("t", List.of(mp.addObjectXml(JDOE)));

        List<Difference> differences = GoldenComparison.compare(golden, actual);
        assertThat(differences).extracting(d -> d.kind + " " + d.identity + " " + d.note)
                .containsExactly("CHANGED " + POLICY_RULE + " null");
        assertThat(GoldenComparison.fails(differences, GoldenComparison.DEFAULT_FAILING)).isTrue();

        // midPoint's own archetype of approval tasks (4.4+; 4.0 has no task archetypes): its handler mapping is a
        // baseline place, a difference there is a note
        if (!Lines.atLeast(mp, "4.4")) {
            return;
        }
        GoldenResults.TestResult task = GoldenResults.of("t", List.of(mp.addObjectXml("<task " + Fixtures.NS
                + "><name>approve things</name><assignment><targetRef oid=\"00000000-0000-0000-0000-000000000521\""
                + " type=\"ArchetypeType\"/></assignment>"
                + "<ownerRef oid=\"00000000-0000-0000-0000-000000000002\" type=\"UserType\"/>"
                + "<executionState>suspended</executionState></task>")));
        GoldenResults.Place archetypePlace = task.runs.get(1).places.values().stream()
                .filter(p -> p.identity.startsWith("archetype:Approval-related task/")).findFirst().orElseThrow();
        assertThat(archetypePlace.flags).contains("baseline");
        GoldenResults file = new GoldenResults("4.8");
        file.put(task);
        String text = file.write("GoldenTest");
        String changed = text.replace("place " + archetypePlace.identity + "\n", "place " + archetypePlace.identity
                + "\n      out new=[\"changed\"]\n");
        List<Difference> atBaseline = GoldenComparison.compare(GoldenResults.parse(text).tests().get("t"),
                GoldenResults.parse(changed).tests().get("t"));
        assertThat(atBaseline).extracting(d -> d.kind + " " + d.identity + " " + d.note)
                .containsExactly("CHANGED " + archetypePlace.identity + " in midPoint's own objects");
        assertThat(GoldenComparison.fails(atBaseline, GoldenComparison.DEFAULT_FAILING)).isFalse();
        // golden.baseline=compare lets it fail
        GoldenComparison.Options compare = GoldenComparison.Options.defaults();
        compare.compareBaseline = true;
        assertThat(GoldenComparison.fails(GoldenComparison.compare(GoldenResults.parse(text).tests().get("t"),
                GoldenResults.parse(changed).tests().get("t"), compare), GoldenComparison.DEFAULT_FAILING)).isTrue();
    }

    @Test
    void countsAndInputsAreNotes() {
        GoldenResults golden = GoldenResults.parse("line 4.8\n\ntest t\n  run 2 add user:jdoe\n"
                + "    place role:A/inducement[#2]/focusMappings/mapping[m]\n      flags ordinal\n      count 2\n"
                + "      out old=[] new=[\"x\"]\n      vars focus, operation\n");
        GoldenResults actual = GoldenResults.parse("line 4.10\n\ntest t\n  run 2 add user:jdoe\n"
                + "    place role:A/inducement[#2]/focusMappings/mapping[m]\n      flags ordinal\n      count 3\n"
                + "      out old=[] new=[\"x\"]\n      vars focus, operation, user\n");

        List<Difference> differences = GoldenComparison.compare(golden.tests().get("t"), actual.tests().get("t"));

        assertThat(differences.stream().map(d -> d.kind).collect(Collectors.toList()))
                .containsExactly(Kind.COUNT, Kind.INPUTS);
        assertThat(GoldenComparison.fails(differences, GoldenComparison.DEFAULT_FAILING)).isFalse();
        assertThat(GoldenComparison.markdown("GoldenTest.t", differences, GoldenComparison.DEFAULT_FAILING))
                .contains("passed (0 failing, 2 notes)")
                .contains("- note: count `role:A/inducement[#2]/focusMappings/mapping[m]` _(ordinal)_\n"
                        + "  - evaluations: 2 -> 3\n")
                .contains("  - golden: `vars `\n  - now: `vars +user`\n");
        assertThat(GoldenComparison.compare(null, actual.tests().get("t")))
                .extracting(d -> d.kind).containsExactly(Kind.NOT_RECORDED);
    }

    @Test
    void runsAreKeyedByStepOrLabel(MidPoint mp) {
        Run added = mp.addObjectXml(JDOE);
        mp.step("relocate");
        Run modified = mp.modify(UserType.class, "jdoe", UserType.F_LOCALITY, "Tokyo");
        Run again = mp.modify(UserType.class, "jdoe", UserType.F_LOCALITY, "Osaka");
        mp.step(null);
        Run recomputed = mp.recompute(UserType.class, "jdoe");

        // labels do not depend on midPoint's delta text or on generated OIDs: objects by name
        assertThat(added.operation()).isEqualTo("add user:jdoe");
        assertThat(modified.operation()).isEqualTo("execute modify user:jdoe [locality]");
        GoldenResults.TestResult result = GoldenResults.of("t", List.of(added, modified, again, recomputed));
        assertThat(result.runs.values()).extracting(r -> r.key).containsExactly(
                "add user:jdoe", "relocate", "relocate #2", "recompute jdoe");
        assertThat(result.runs.get(2).operation).isEqualTo("execute modify user:jdoe [locality]");
        GoldenResults file = new GoldenResults("4.8");
        file.put(result);
        String text = file.write("GoldenTest");
        assertThat(text).contains("\n  run 2 relocate\n    label execute modify user:jdoe [locality]\n");
        assertThat(GoldenResults.parse(text).write("GoldenTest")).isEqualTo(text);
    }

    @Test
    void runsAreMatchedByKeyThenByNumber() {
        String place = "\n    place role:A/mapping[m]\n      count 1\n      out new=[\"x\"]\n";
        GoldenResults golden = GoldenResults.parse("line 4.8\n\ntest t\n  run 1 add user:jdoe" + place
                + "  run 2 relocate" + place);
        // another line needs one more driver call before the step: matched by key, not by number
        GoldenResults moved = GoldenResults.parse("line 4.10\n\ntest t\n  run 1 add user:jdoe" + place
                + "  run 3 relocate" + place);
        assertThat(GoldenComparison.compare(golden.tests().get("t"), moved.tests().get("t"))).isEmpty();
        // a renamed run is matched by its number, with a note
        GoldenResults renamed = GoldenResults.parse("line 4.10\n\ntest t\n  run 1 add user:jdoe" + place
                + "  run 2 move" + place);
        List<Difference> differences = GoldenComparison.compare(golden.tests().get("t"), renamed.tests().get("t"));
        assertThat(differences).extracting(d -> d.kind).containsExactly(Kind.RUN);
        assertThat(GoldenComparison.markdown("GoldenTest.t", differences, GoldenComparison.DEFAULT_FAILING))
                .contains("passed (0 failing, 1 notes)").contains("Run 2: `move`")
                .contains("- note: run matched by number, its key changed\n  - golden: `relocate`\n  - now: `move`\n");
    }

    @Test
    void onlyOldValuesOrTextDifferAreNotes() {
        // 4.9+ give the inbound of a just created account no old value: the same result
        assertThat(kinds("condition true->true new=[\"carol\"]", "condition true->true new=[\"carol\"] | old=[]"))
                .containsExactly(Kind.OLD);
        assertThat(kinds("condition false->true new=[\"x\"]", "condition true->true new=[\"x\"]"))
                .containsExactly(Kind.OLD);
        // but a value that one line removes is a change
        assertThat(kinds("new=[] removed=[\"x\"] | old=[\"x\"]", "new=[] | old=[]")).containsExactly(Kind.CHANGED);
        assertThat(kinds("new=[\"x\"]", "new=[\"y\"]")).containsExactly(Kind.CHANGED);
        // line-dependent wording: the same error class, the JSON form of a container
        assertThat(kinds("error SchemaException: No such item", "error SchemaException: Item not found"))
                .containsExactly(Kind.TEXT);
        assertThat(kinds("error SchemaException: x", "error ConfigurationException: x")).containsExactly(Kind.CHANGED);
        assertThat(kinds("new=[Information{\"title\" : \"a\"}]", "new=[Information{\"@type\": \"x\", \"title\": \"a\"}]"))
                .containsExactly(Kind.TEXT);
        assertThat(GoldenComparison.DEFAULT_FAILING).doesNotContain(Kind.OLD, Kind.TEXT);
    }

    @Test
    void newStateOptionComparesWhatTheOutcomesLeave() {
        // 4.0 keeps a template mapping's evaluation with the old state of the operation last (RecorderTest.mappingWithError)
        String oldStateLast = "condition true->true new=[\"John Doe\"] removed=[\"null null\"] | old=[\"null null\"]";
        GoldenComparison.Options newState = GoldenComparison.Options.defaults();
        newState.newState = true;
        assertThat(kinds(oldStateLast, "condition true->true new=[\"John Doe\"]")).containsExactly(Kind.CHANGED);
        assertThat(kinds(oldStateLast, "condition true->true new=[\"John Doe\"]", newState))
                .containsExactly(Kind.OLD_STATE);
        // a value only one line removes
        assertThat(kinds("new=[] removed=[\"x\"] | old=[\"x\"]", "new=[] | old=[]", newState))
                .containsExactly(Kind.OLD_STATE);
        // under a false new condition the output does not matter, unless it is an error
        assertThat(kinds("condition true->false new=[] removed=[\"a\"] | old=[\"a\"]", "condition false->false no output",
                newState)).containsExactly(Kind.OLD_STATE);
        assertThat(kinds("condition true->false error IllegalStateException: x", "condition false->false no output",
                newState)).containsExactly(Kind.CHANGED);
        // what the new state gets still counts
        assertThat(kinds("condition true->true new=[\"a\"]", "condition false->false no output", newState))
                .containsExactly(Kind.CHANGED);
        assertThat(kinds("new=[\"a\"] removed=[\"b\"] | old=[\"b\"]", "new=[\"c\"] removed=[\"b\"] | old=[\"b\"]",
                newState)).containsExactly(Kind.CHANGED);
        assertThat(GoldenComparison.DEFAULT_FAILING).doesNotContain(Kind.OLD_STATE);

        // the option is a line of the accepted file, with its reason; the acceptances skip it
        String file = "option new-state  # 4.0 keeps the old state last\nchanged | GoldenTest.t | * | role:A/*  # y\n";
        List<GoldenComparison.Option> options = GoldenComparison.parseOptions(file);
        assertThat(options).extracting(o -> o.name + ": " + o.reason)
                .containsExactly("new-state: 4.0 keeps the old state last");
        GoldenComparison.Options parsed = GoldenComparison.Options.defaults();
        GoldenComparison.apply(options, parsed);
        assertThat(parsed.newState).isTrue();
        assertThat(GoldenComparison.parseAccepted(file)).hasSize(1);
        assertThatThrownBy(() -> GoldenComparison.parseOptions("\noption everything  # no\n"))
                .hasMessageContaining("line 2").hasMessageContaining("unknown option everything");
    }

    @Test
    void movedPlacesAreComparedAtTheirIdentityNow() {
        // 4.8's resource format moved the correlation from synchronization into the object type
        String from = "resource:HR/synchronization/objectSynchronization[account/default]/correlation/equal/expression";
        String to = "resource:HR/schemaHandling/objectType[account/default]/correlation/correlators/filter/ownerFilter"
                + "/equal/expression";
        String head = "line 4.8\n\ntest t\n  run 1 import account jdoe\n    place ";
        GoldenResults.TestResult golden = GoldenResults.parse(head + from + "\n      count 1\n      out new=[\"jdoe\"]\n")
                .tests().get("t");
        GoldenResults.TestResult same = GoldenResults.parse(head + to + "\n      count 1\n      out new=[\"jdoe\"]\n")
                .tests().get("t");
        GoldenResults.TestResult changed = GoldenResults.parse(head + to + "\n      count 1\n      out new=[\"JDOE\"]\n")
                .tests().get("t");
        assertThat(GoldenComparison.compare(golden, same)).extracting(d -> d.kind)
                .containsExactlyInAnyOrder(Kind.MISSING, Kind.NEW);

        GoldenComparison.Options options = GoldenComparison.Options.defaults();
        GoldenComparison.apply(GoldenComparison.parseOptions("option moved " + from + " -> " + to + "  # 4.8 format\n"),
                options);
        assertThat(GoldenComparison.compare(golden, same, options)).isEmpty();
        List<Difference> differences = GoldenComparison.compare(golden, changed, options);
        assertThat(differences).extracting(d -> d.kind + " " + d.identity).containsExactly("CHANGED " + to);
        assertThat(differences.get(0).flags).containsExactly("moved from " + from);
        assertThatThrownBy(() -> GoldenComparison.parseOptions("option moved " + from + "\n"))
                .hasMessageContaining("line 1").hasMessageContaining("option moved <golden identity> -> <identity>");
    }

    @Test
    void intermediateOutcomesAreComparedOnRequest() {
        String golden = "line 4.8\n\ntest t\n  run 1 add user:jdoe\n    place role:A/mapping[m]\n      count 2\n"
                + "      out new=[\"x\"]\n      all new=[\"x\"] removed=[\"\"] | old=[\"\"]\n";
        String actual = "line 4.10\n\ntest t\n  run 1 add user:jdoe\n    place role:A/mapping[m]\n      count 2\n"
                + "      out new=[\"x\"]\n      all new=[\"x\"] | old=[]\n";
        GoldenResults.TestResult a = GoldenResults.parse(golden).tests().get("t");
        GoldenResults.TestResult b = GoldenResults.parse(actual).tests().get("t");
        assertThat(GoldenComparison.compare(a, b)).isEmpty();
        GoldenComparison.Options all = GoldenComparison.Options.defaults();
        all.allOutcomes = true;
        assertThat(GoldenComparison.compare(a, b, all)).extracting(d -> d.kind).containsExactly(Kind.CHANGED);
    }

    @Test
    void baselineFailedAndAcceptedDifferencesAreNotes() {
        String head = "line 4.8\n\ntest t\n  run 1 add user:jdoe\n";
        GoldenResults golden = GoldenResults.parse(head
                + "    place archetype:Approval case/policy\n      flags baseline\n      count 1\n      out new=[\"a\"]\n"
                + "    place role:A/mapping[m]\n      count 1\n      out new=[\"x\"]\n");
        GoldenResults actual = GoldenResults.parse(head
                + "    place archetype:Approval case/policy\n      flags baseline\n      count 1\n      out new=[\"b\"]\n"
                + "    place role:A/mapping[m]\n      count 1\n      out new=[\"y\"]\n");
        List<Difference> differences = GoldenComparison.compare(golden.tests().get("t"), actual.tests().get("t"));
        assertThat(differences).extracting(d -> d.kind + " " + d.note).containsExactly(
                "CHANGED in midPoint's own objects", "CHANGED null");
        assertThat(GoldenComparison.fails(differences, GoldenComparison.DEFAULT_FAILING)).isTrue();

        List<GoldenComparison.Acceptance> accepted = GoldenComparison.parseAccepted(
                "# kind | test | run | place\n\nchanged | GoldenTest.* | add user:* | role:A/*  # y since 4.10\n");
        GoldenComparison.accept("GoldenTest.t", differences, accepted);
        assertThat(GoldenComparison.fails(differences, GoldenComparison.DEFAULT_FAILING)).isFalse();
        assertThat(GoldenComparison.markdown("GoldenTest.t", differences, GoldenComparison.DEFAULT_FAILING))
                .contains("passed (0 failing, 2 notes)")
                .contains("- note: changed `role:A/mapping[m]` - accepted: y since 4.10\n")
                .contains("- note: changed `archetype:Approval case/policy` _(baseline)_ - in midPoint's own objects\n");
        // the place of the baseline object comes last
        String md = GoldenComparison.markdown("GoldenTest.t", differences, GoldenComparison.DEFAULT_FAILING);
        assertThat(md.indexOf("role:A")).isLessThan(md.indexOf("archetype:"));
        // '#' in an ordinal identity is not a comment
        GoldenComparison.Acceptance ordinal = GoldenComparison.parseAccepted(
                "* | GoldenTest.t | * | role:Mailer/inducement[#3]/*  # ordinal\n").get(0);
        Difference atOrdinal = GoldenComparison.compare(
                GoldenResults.parse(head + "    place role:Mailer/inducement[#3]/focusMappings/mapping[m]\n"
                        + "      count 1\n      out new=[\"a\"]\n").tests().get("t"),
                GoldenResults.parse(head + "    place role:Mailer/inducement[#3]/focusMappings/mapping[m]\n"
                        + "      count 1\n      out new=[\"b\"]\n").tests().get("t")).get(0);
        GoldenComparison.accept("GoldenTest.t", List.of(atOrdinal), List.of(ordinal));
        assertThat(atOrdinal.note).isEqualTo("accepted: ordinal");
        // nor '#' in the run key of a repeated label
        GoldenComparison.Acceptance repeated = GoldenComparison.parseAccepted(
                "changed | GoldenTest.t | add user:jdoe #2 | role:Mailer/*  # repeated\n").get(0);
        Difference atRepeated = GoldenComparison.compare(
                GoldenResults.parse("line 4.8\n\ntest t\n  run 2 add user:jdoe #2\n    place role:Mailer/mapping[m]\n"
                        + "      count 1\n      out new=[\"a\"]\n").tests().get("t"),
                GoldenResults.parse("line 4.8\n\ntest t\n  run 2 add user:jdoe #2\n    place role:Mailer/mapping[m]\n"
                        + "      count 1\n      out new=[\"b\"]\n").tests().get("t")).get(0);
        GoldenComparison.accept("GoldenTest.t", List.of(atRepeated), List.of(repeated));
        assertThat(atRepeated.note).isEqualTo("accepted: repeated");
        // an entry for a test also matches each invocation of the repeated test
        atRepeated.note = null;
        GoldenComparison.accept("GoldenTest.t repetition 2 of 4", List.of(atRepeated), List.of(repeated));
        assertThat(atRepeated.note).isEqualTo("accepted: repeated");
        atRepeated.note = null;
        GoldenComparison.accept("GoldenTest.tt repetition 2 of 4", List.of(atRepeated), List.of(repeated));
        assertThat(atRepeated.note).isNull();
        assertThatThrownBy(() -> GoldenComparison.parseAccepted("changed | t | r\n"))
                .hasMessageContaining("line 1");

        // a test recorded while failing is marked
        String failed = "line 4.8\n\ntest t\n  failed AssertionError: expected x\n  run 1 add user:jdoe\n"
                + "    place role:A/mapping[m]\n      count 1\n      out new=[\"x\"]\n";
        GoldenResults parsed = GoldenResults.parse(failed);
        assertThat(parsed.tests().get("t").failure).isEqualTo("AssertionError: expected x");
        assertThat(parsed.write("x")).endsWith(failed.substring("line 4.8\n".length()));
    }

    private static List<Kind> kinds(String golden, String actual) {
        return kinds(golden, actual, GoldenComparison.Options.defaults());
    }

    private static List<Kind> kinds(String golden, String actual, GoldenComparison.Options options) {
        String head = "line 4.8\n\ntest t\n  run 1 add user:jdoe\n    place role:A/mapping[m]\n      count 1\n      out ";
        return GoldenComparison.compare(GoldenResults.parse(head + golden + "\n").tests().get("t"),
                GoldenResults.parse(head + actual + "\n").tests().get("t"), options).stream().map(d -> d.kind)
                .collect(Collectors.toList());
    }
}
