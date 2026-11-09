package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.tester.Identity;

/**
 * What tells siblings apart in an identity (docs/redesign.md, "Identity"), read from plain XML: the discriminators
 * midPoint has, then those used only among siblings that the first ones do not tell apart, and the position
 * ({@code #n}) only where nothing else does. No midPoint runtime.
 */
class IdentityKeysTest {

    static final String NS = "xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3' "
            + "xmlns:q='http://prism.evolveum.com/xml/ns/public/query-3'";
    static final String SCRIPT = "<script><code>'x'</code></script>";

    @TempDir
    Path dir;

    @Test
    void inducementsByTargetOrderFocusMappingsAndFilter() throws IOException {
        List<String> ids = identities("<role oid='30000000-0000-0000-0000-000000000001' " + NS + "><name>R</name>"
                // the same target twice: told apart by the order (1 when not given)
                + "<inducement><targetRef oid='30000000-0000-0000-0000-000000000002' type='RoleType'/>"
                + "<condition><expression>" + SCRIPT + "</expression></condition></inducement>"
                + "<inducement><targetRef oid='30000000-0000-0000-0000-000000000002' type='RoleType'/><order>2</order>"
                + "<condition><expression>" + SCRIPT + "</expression></condition></inducement>"
                // focus mappings: by their names, or target paths
                + "<inducement><focusMappings><mapping><name>m1</name><expression>" + SCRIPT + "</expression>"
                + "<target><path>title</path></target></mapping></focusMappings></inducement>"
                + "<inducement><focusMappings><mapping><expression>" + SCRIPT + "</expression>"
                + "<target><path>description</path></target></mapping></focusMappings></inducement>"
                // a target given by a filter: its type and the filtered paths
                + "<inducement><targetRef type='OrgType'><filter><q:equal><q:path>costCenter</q:path>"
                + "<expression><path>$focus/costCenter</path></expression></q:equal></filter></targetRef></inducement>"
                + "</role><role oid='30000000-0000-0000-0000-000000000002' " + NS + "><name>Base</name></role>");

        assertThat(ids).containsExactly(
                "role:R/inducement[target=Base, order=1]/condition/expression",
                "role:R/inducement[target=Base, order=2]/condition/expression",
                "role:R/inducement[focusMappings=m1]/focusMappings/mapping[m1]/expression",
                "role:R/inducement[focusMappings=description]/focusMappings/mapping[target=description]/expression",
                "role:R/inducement[targetFilter=org:costCenter]/targetRef/filter/equal/expression");
    }

    @Test
    void anElementAloneWithoutADiscriminatorNeedsNone() throws IOException {
        // one inducement without a discriminator among inducements with one: nothing to tell it apart from
        List<String> ids = identities("<role oid='30000000-0000-0000-0000-000000000003' " + NS + "><name>R</name>"
                + "<inducement><targetRef oid='30000000-0000-0000-0000-000000000004' type='RoleType'/>"
                + "<condition><expression>" + SCRIPT + "</expression></condition></inducement>"
                + "<inducement><focusMappings><mapping><name>m</name><expression>" + SCRIPT + "</expression>"
                + "<target><path>title</path></target></mapping></focusMappings></inducement></role>");

        assertThat(ids).containsExactly(
                "role:R/inducement[target=30000000-0000-0000-0000-000000000004]/condition/expression",
                "role:R/inducement/focusMappings/mapping[m]/expression");
    }

    @Test
    void siblingsByWhatTheirTypeHas() throws IOException {
        List<String> ids = identities("<systemConfiguration oid='30000000-0000-0000-0000-000000000005' " + NS + ">"
                + "<name>S</name><defaultObjectPolicyConfiguration><lifecycleStateModel><state><name>active</name>"
                + "<transition><targetState>suspended</targetState><condition>" + SCRIPT + "</condition></transition>"
                + "<transition><targetState>archived</targetState><condition>" + SCRIPT + "</condition></transition>"
                + "</state></lifecycleStateModel></defaultObjectPolicyConfiguration></systemConfiguration>"
                + "<valuePolicy oid='30000000-0000-0000-0000-000000000006' " + NS + "><name>P</name><stringPolicy>"
                + "<limitations><checkExpression><expression>" + SCRIPT + "</expression>"
                + "<failureMessage>must not contain   \"secret\"</failureMessage></checkExpression>"
                + "<checkExpression><expression>" + SCRIPT + "</expression>"
                + "<failureMessage>a failure message much longer than forty characters</failureMessage>"
                + "</checkExpression></limitations></stringPolicy></valuePolicy>");

        assertThat(ids).containsExactly(
                "systemConfiguration:S/defaultObjectPolicyConfiguration/lifecycleStateModel/state[active]"
                        + "/transition[targetState=suspended]/condition",
                "systemConfiguration:S/defaultObjectPolicyConfiguration/lifecycleStateModel/state[active]"
                        + "/transition[targetState=archived]/condition",
                // free text: whitespace collapsed, cut after 40 characters
                "valuePolicy:P/stringPolicy/limitations/checkExpression[failureMessage=must not contain \"secret\"]"
                        + "/expression",
                "valuePolicy:P/stringPolicy/limitations/checkExpression[failureMessage=a failure message much "
                        + "longer than forty...]/expression");
    }

    @Test
    void descriptionsArgumentsAndWhatIsLeftToPositions() throws IOException {
        List<String> ids = identities("<dashboard oid='30000000-0000-0000-0000-000000000007' " + NS + "><name>D</name>"
                + "<widget><identifier>w</identifier><presentation>"
                + "<dataField><fieldType>value</fieldType><expression>" + SCRIPT + "</expression></dataField>"
                + "<dataField><fieldType>unit</fieldType><expression>" + SCRIPT + "</expression></dataField>"
                + "<variation><condition><description>none</description>" + SCRIPT + "</condition></variation>"
                + "<variation><condition><description>some</description>" + SCRIPT + "</condition></variation>"
                // nothing tells these two apart: their position
                + "<variation><condition>" + SCRIPT + "</condition></variation>"
                + "<variation><condition>" + SCRIPT + "</condition></variation>"
                + "</presentation></widget></dashboard>"
                + "<role oid='30000000-0000-0000-0000-000000000008' " + NS + "><name>A</name><assignment><policyRule>"
                + "<name>p</name><policyActions><approval><approvalDisplayName><argumentExpression>" + SCRIPT
                + "</argumentExpression><argumentExpression>" + SCRIPT + "</argumentExpression></approvalDisplayName>"
                + "<approvalSchema><stage><number>1</number>"
                + "<approverExpression><description>the owner</description>" + SCRIPT + "</approverExpression>"
                + "<approverExpression><description>the  manager</description>" + SCRIPT + "</approverExpression>"
                + "</stage></approvalSchema></approval></policyActions></policyRule></assignment></role>");

        String widget = "dashboard:D/widget[identifier=w]/presentation/";
        String approval = "role:A/assignment[policyRule=p]/policyRule[p]/policyActions/approval/";
        assertThat(ids).containsExactly(
                widget + "dataField[fieldType=value]/expression",
                widget + "dataField[fieldType=unit]/expression",
                widget + "variation[condition=none]/condition",
                widget + "variation[condition=some]/condition",
                widget + "variation[#3]/condition",
                widget + "variation[#4]/condition",
                // the arguments of a message are their position
                approval + "approvalDisplayName/argumentExpression[argument=1]",
                approval + "approvalDisplayName/argumentExpression[argument=2]",
                approval + "approvalSchema/stage[number=1]/approverExpression[description=the owner]",
                approval + "approvalSchema/stage[number=1]/approverExpression[description=the manager]");
    }

    /** As midPoint exports objects ({@code <object xsi:type="c:RoleType">}): the same identities as {@code <role>}. */
    @Test
    void anObjectOfATypeIsThatType() throws IOException {
        List<String> ids = identities("<object oid='30000000-0000-0000-0000-000000000009' xsi:type='c:RoleType' "
                + "xmlns:c='http://midpoint.evolveum.com/xml/ns/public/common/common-3' "
                + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><name>R</name>"
                + "<condition><expression>" + SCRIPT + "</expression></condition></object>");

        assertThat(ids).containsExactly("role:R/condition/expression");
    }

    private List<String> identities(String objects) throws IOException {
        Path file = dir.resolve("objects.xml");
        Files.writeString(file, ("<objects " + NS + ">" + objects + "</objects>").replace("><", ">\n<"));
        ConfigFiles files = ConfigFiles.create();
        files.read(file, "objects.xml");
        return files.places().stream().map(p -> p.identity).map(Identity::toString).collect(Collectors.toList());
    }
}
