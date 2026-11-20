package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * A deployment without a system configuration of its own runs on midPoint's, as a fresh installation has it. 4.11's
 * makes MEL the default script language ({@code expressions/defaultScriptLanguage}) and gives the expressions of
 * every object type it names a profile without Groovy, an administrator's bulk action the {@code configuration}
 * profile ({@code expressions/defaults/privilegedPrincipal}): such a deployment scripts in MEL only.
 */
@MidPointConfigTest("classpath:scenarios/fresh/midpoint-config-tester.yaml")
class FreshInstallationTest {

    private static final String GROOVY = "http://midpoint.evolveum.com/xml/ns/public/expression/language#Groovy";

    @Test
    void aScriptWithoutALanguageIsMel(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        assumeTrue(Lines.atLeast(mp, "4.11"), "MEL is the default script language from 4.11 (ScriptFactory)");
        // size() is MEL's; Groovy has no such function
        assertThat(mp.script("size('abc')").language(null).evaluate().value()).containsExactly("3");
    }

    @Test
    void aBulkActionScriptInMelIsRecorded(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        assumeTrue(Lines.atLeast(mp, "4.11"), "MEL from 4.11");
        // a bulk action's script runs on the script factory directly: the hook wraps its MEL executor too
        Answer<BulkActionOutput> out = mp.bulkAction(
                "<s:executeScript xmlns:s='http://midpoint.evolveum.com/xml/ns/public/model/scripting-3'"
                        + " xmlns:c='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                        + "<s:action><s:type>execute-script</s:type>"
                        + "<s:parameter><s:name>script</s:name><c:value><c:code>size('abc')</c:code></c:value>"
                        + "</s:parameter>"
                        + "<s:parameter><s:name>forWholeInput</s:name><c:value>true</c:value></s:parameter>"
                        + "</s:action></s:executeScript>");
        assertThat(out.run().evaluations()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Evaluation.Kind.EXPRESSION);
            assertThat(e.fingerprint()).contains("size('abc')");
        });
    }

    @Test
    void anAdministratorsBulkActionInGroovyIsRefused(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        assumeTrue(Lines.atLeast(mp, "4.11"), "expression profiles by object type and principal from 4.11");
        assertThatThrownBy(() -> mp.bulkAction(
                "<s:executeScript xmlns:s='http://midpoint.evolveum.com/xml/ns/public/model/scripting-3'"
                        + " xmlns:c='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                        + "<s:action><s:type>execute-script</s:type>"
                        + "<s:parameter><s:name>script</s:name><c:value>"
                        + "<c:language>" + GROOVY + "</c:language><c:code>'x'</c:code>"
                        + "</c:value></s:parameter>"
                        + "<s:parameter><s:name>forWholeInput</s:name><c:value>true</c:value></s:parameter>"
                        + "</s:action></s:executeScript>"))
                .isInstanceOf(OperationFailed.class)
                .hasMessageContaining("Script interpreter for language 'Groovy' is not allowed in expression profile"
                        + " 'configuration'");
    }

    @Test
    void aMembersPanelsTaskIsRefusedByTheInitialExpressionProfilesOf411(MidPointConfigTester t) {
        // the task of a members panel's Assign runs the bulk action assign as the user who started it
        // (MembersPanelScenarioTest); 4.11's initial system configuration gives it the prohibitive profile, whose
        // actions profile allows no bulk action (BaseActionExecutor.checkExecutionAllowed): the task fails on each
        // member. Before 4.11 there is no profile: the task assigns
        t.midpoint().importObject(new java.io.File(Fixtures.dir("forms"), "member-operations.xml"));
        com.github.wadahiro.midpoint.configtester.MembersPanel tree = t.actor("mo-site-manager")
                .orgTreeMembersPanel(t.org("mo-site-1"));

        if (Lines.atLeast(t.midpoint(), "4.11")) {
            assertThat(t.expectFailure(() -> tree.assign(SchemaConstants.ORG_MANAGER, t.user("mo-jack"))).message())
                    .contains("failed: the task \"Add members to Organization mo-site-1\" (owned by mo-site-manager)"
                            + " failed: PARTIAL_ERROR")
                    .contains("last failure: mo-jack: Access to action 'assign' denied (applied expression profile"
                            + " 'prohibitive', actions profile 'prohibitive')");
            t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
            return;
        }
        tree.assign(SchemaConstants.ORG_MANAGER, t.user("mo-jack"));
        t.user("mo-jack").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }
}
