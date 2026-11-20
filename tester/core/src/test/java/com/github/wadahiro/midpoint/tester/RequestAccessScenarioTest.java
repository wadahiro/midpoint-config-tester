package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentConstraintsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionUseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionsUseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleManagementConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.github.wadahiro.midpoint.configtester.CatalogEntry;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.RequestAccessPage;
import com.github.wadahiro.midpoint.configtester.RoleVisibility;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.RoleRef;
import com.github.wadahiro.midpoint.configtester.names.ServiceRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;

/**
 * The request-access page as a user sees it ({@code Actor.requestAccess()}), computed as the line's GUI computes it:
 * 4.0 and 4.4 the shopping cart of the system configuration's {@code roleManagement}, 4.8 and later the wizard of the
 * user's GUI profile's {@code accessRequest} (fixtures/request-access: catalog.xml, config.xml and its 4.4 variant).
 * The system configuration's {@code roleManagement} names the same catalog on every line: 4.8 and later do not read it.
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class RequestAccessScenarioTest {

    private static final RoleRef VPN = new RoleRef("rc-vpn", null, Lines.ALL);
    private static final RoleRef ADMIN_TOOLS = new RoleRef("rc-admin-tools", null, Lines.ALL);
    private static final RoleRef CRM = new RoleRef("rc-crm", null, Lines.ALL);
    private static final RoleRef OUTSIDE = new RoleRef("rc-outside", null, Lines.ALL);
    private static final RoleRef DEEP = new RoleRef("rc-deep", null, Lines.ALL);
    private static final ServiceRef WIKI = new ServiceRef("rc-wiki", null, Lines.ALL);
    private static final UserRef JACK = new UserRef("rc-jack", null, Lines.ALL);
    private static final UserRef ANN = new UserRef("rc-ann", null, Lines.ALL);
    private static final UserRef BOB = new UserRef("rc-bob", null, Lines.ALL);

    private static void importFixture(MidPointConfigTester t, AssignmentConstraintsType constraints) {
        t.midpoint().importObject(Fixtures.of(t.midpoint(), new File(Fixtures.dir("request-access"), "config.xml")));
        t.midpoint().importObject(new File(Fixtures.dir("request-access"), "catalog.xml"));
        // the shopping cart's configuration (4.0, 4.4); 4.8 and later do not read roleCatalogRef
        RoleManagementConfigurationType roleManagement = new RoleManagementConfigurationType()
                .roleCatalogRef(new ObjectReferenceType().oid("10000000-0000-0000-0000-0000000f0801")
                        .type(OrgType.COMPLEX_TYPE))
                .roleCatalogCollections(new ObjectCollectionsUseType()
                        .collection(new ObjectCollectionUseType().collectionUri(
                                SchemaConstants.OBJECT_COLLECTION_ROLE_CATALOG_URI))
                        .collection(new ObjectCollectionUseType().collectionUri(
                                SchemaConstants.OBJECT_COLLECTION_ALL_ROLES_URI)))
                .defaultAssignmentConstraints(constraints);
        t.midpoint().modify(SystemConfigurationType.class, "00000000-0000-0000-0000-000000000001",
                ItemPath.create(SystemConfigurationType.F_ROLE_MANAGEMENT), roleManagement);
    }

    private static boolean wizard(MidPointConfigTester t) {
        return !t.line().equals("4.0") && !t.line().equals("4.4");
    }

    @Test
    void anEndUserIsOfferedTheRequestableRolesOfTheCatalog(MidPointConfigTester t) {
        importFixture(t, null);

        RequestAccessPage page = t.actor("rc-jack").requestAccess().offersRole(VPN).offersRole(CRM).offersRole(DEEP)
                .showsNoRole(ADMIN_TOOLS);
        assertThat(page.target()).isEqualTo(JACK);
        assertThat(page.relation()).isEqualTo(SchemaConstants.ORG_DEFAULT);
        assertThat(page.relations()).containsExactly(SchemaConstants.ORG_DEFAULT);
        assertThat(page.roles()).containsExactlyInAnyOrder(VPN, CRM, DEEP, OUTSIDE);
        // a folder lists the roles one level in it; the search panel's type switched, its services
        assertThat(page.entry("rc-it").roles()).containsExactly(VPN);
        assertThat(page.entry("rc-it").shows(ADMIN_TOOLS)).isFalse();
        assertThat(page.withType(ServiceType.class).entry("rc-it").roles()).containsExactly(WIKI);
        assertThat(page.withType(ServiceType.class).offers(WIKI)).isTrue();
        assertThat(page.offers(WIKI)).isFalse();
        assertThat(page.entry("rc-it").children()).extracting(CatalogEntry::key).containsExactly("rc-it-sub");
        assertThat(page.entry("rc-it-sub").kind()).isEqualTo(CatalogEntry.Kind.ORG_FOLDER);
        assertThat(page.entry("allRoles").kind()).isEqualTo(CatalogEntry.Kind.ALL_ROLES);
        // the role outside the catalog: only through All roles (4.8+ also the collection of requestable roles)
        assertThat(page.entry("allRoles").roles()).contains(OUTSIDE);
        if (wizard(t)) {
            // the menu of the GUI profile's accessRequest: the catalog org's children, its collections, teammates
            assertThat(page.entries()).extracting(CatalogEntry::toString).containsExactly("rc-it (folder)",
                    "rc-sales (folder)", "rc-requestable (collection)", "allRoles", "rolesOfTeammate");
            assertThat(page.entry("rc-requestable").roles()).containsExactlyInAnyOrder(VPN, CRM, DEEP, OUTSIDE);
        } else {
            // the tabs of roleManagement: the role catalog (the catalog org itself) and All roles
            assertThat(page.entries()).extracting(CatalogEntry::key).containsExactly("roleCatalog", "allRoles");
            assertThat(page.entry("roleCatalog").children()).extracting(CatalogEntry::key)
                    .containsExactly("rc-it", "rc-sales");
        }

        RoleVisibility tools = page.explain(ADMIN_TOOLS);
        assertThat(tools.listedIn()).extracting(CatalogEntry::key).contains("rc-it", "allRoles");
        assertThat(tools.assignable()).isFalse();
        assertThat(tools.foundByRequester()).isTrue();
        assertThatThrownBy(() -> page.showsRole(ADMIN_TOOLS)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("rc-jack: the request-access page for rc-jack (relation default) does not"
                        + " show Role rc-admin-tools: it is in the catalog (")
                .hasMessageContaining("but rc-jack's assign authorizations do not allow assigning it to rc-jack"
                        + " with relation default");
        assertThatThrownBy(() -> page.showsNoRole(VPN)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("rc-jack: the request-access page for rc-jack (relation default) shows Role"
                        + " rc-vpn in: rc-it (folder)");
        assertThatThrownBy(() -> page.entry("Finance")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("rc-jack: the request-access page has no entry \"Finance\"; its entries: ");

        // the page's Submit is the request
        t.actor("rc-jack").request().assign(VPN);
        t.user("rc-jack").hasAssignment(VPN);
    }

    @Test
    void whomAUserMayRequestFor(MidPointConfigTester t) {
        importFixture(t, null);

        t.actor("rc-jack").requestAccess().offersTarget(JACK).offersNoTarget(ANN);
        RequestAccessPage manager = t.actor("rc-manager").requestAccess(JACK).offersTarget(JACK).offersTarget(BOB);
        if (wizard(t)) {
            // the object part of the #assign authorizations; a group's collection narrows only its tile
            manager.offersNoTarget(ANN);
            assertThat(manager.inGroup("team", JACK)).isTrue();
            assertThat(manager.inGroup("team", BOB)).isFalse();
            assertThatThrownBy(() -> manager.offersTarget(ANN)).isInstanceOf(AssertionError.class)
                    .hasMessage("rc-manager: the request-access page does not let rc-manager request for User rc-ann:"
                            + " User rc-ann is not found by rc-manager's search with the object filter of"
                            + " rc-manager's #assign authorizations");
            // accessRequest of rc-ar-narrow: no requests for others
            t.actor("rc-narrow").requestAccess().offersTarget(new UserRef("rc-narrow", null, Lines.ALL))
                    .offersNoTarget(JACK);
        } else {
            // any user the requester's search finds
            manager.offersTarget(ANN);
            assertThatThrownBy(() -> manager.inGroup("team", JACK)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("rc-manager: the request-access page has no group team; its groups: none");
        }
        assertThatThrownBy(() -> t.actor("rc-no-page").requestAccess()).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("rc-no-page may not open "
                        + (wizard(t) ? "/self/requestAccess" : "/self/assignmentShoppingCart"));
    }

    @Test
    void theRelationsOfTheRequestersAssignAuthorizations(MidPointConfigTester t) {
        importFixture(t, null);

        RequestAccessPage manager = t.actor("rc-manager").requestAccess(JACK);
        // the relations of #assign authorizations naming one; the page's default stays default
        assertThat(manager.relations()).containsExactly(SchemaConstants.ORG_MANAGER);
        assertThat(manager.defaultRelation()).isEqualTo(SchemaConstants.ORG_DEFAULT);
        manager.withRelation(SchemaConstants.ORG_MANAGER).offersRole(CRM).offersRole(ADMIN_TOOLS);
        // with the default relation the specification has neither a global filter nor one of the relation: the GUI's
        // query then has no filter of what may be assigned and lists every role (the request is refused at Submit)
        manager.showsRole(CRM).showsRole(ADMIN_TOOLS);
        // #modify of the user's assignments: everything
        assertThat(t.actor("rc-modify-all").requestAccess().roles()).contains(VPN, ADMIN_TOOLS, CRM, DEEP, OUTSIDE);
        if (wizard(t)) {
            RequestAccessPage narrow = t.actor("rc-narrow").requestAccess();
            assertThat(narrow.relations()).containsExactly(SchemaConstants.ORG_DEFAULT);
            // roleCatalogDepth 1: no sub-folders, no roles of teammate
            assertThat(narrow.entries()).extracting(CatalogEntry::toString).containsExactly("rc-it (folder)",
                    "rc-sales (folder)");
            assertThat(narrow.entry("rc-it").children()).isEmpty();
            narrow.showsNoRole(DEEP);
        }
    }

    @Test
    void aRoleTheRequesterCannotSearchIsNotShown(MidPointConfigTester t) {
        importFixture(t, null);

        RequestAccessPage page = t.actor("rc-no-search").requestAccess().showsNoRole(VPN).showsRole(CRM);
        RoleVisibility vpn = page.explain(VPN);
        assertThat(vpn.assignable()).isTrue();
        assertThat(vpn.foundByRequester()).isFalse();
        assertThatThrownBy(() -> page.showsRole(VPN)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("and assignable, but rc-no-search's search of RoleType does not find it"
                        + " (#search or #read)");
    }

    @Test
    void theCatalogOfTheLinesConfiguration(MidPointConfigTester t) {
        importFixture(t, null);

        // rc-plain has no accessRequest of a role: 4.8 and later do not read roleManagement/roleCatalogRef, the
        // compiled profile's collections (all roles, orgs, services) are its entries, no folder
        RequestAccessPage plain = t.actor("rc-plain").requestAccess();
        if (wizard(t)) {
            assertThat(plain.entries()).extracting(CatalogEntry::kind).doesNotContain(CatalogEntry.Kind.ORG_FOLDER);
            assertThatThrownBy(() -> plain.entry("rc-it")).isInstanceOf(IllegalArgumentException.class);
            plain.offersRole(VPN);
            assertThat(plain.explain(VPN).listedIn()).extracting(CatalogEntry::key).containsExactly("allRoles");
            // the roles of a teammate: their assignments' targets with the relation, the teammate read as the
            // requester (rc-jack may not read rc-ann: none), as the requester may assign them
            assertThat(t.actor("rc-manager").requestAccess(JACK).entry("rolesOfTeammate").rolesOfTeammate(ANN))
                    .contains(CRM);
            assertThat(t.actor("rc-jack").requestAccess().entry("rolesOfTeammate").rolesOfTeammate(ANN)).isEmpty();
        } else {
            plain.offersRole(VPN);
            assertThat(t.actor("rc-jack").requestAccess().entries()).extracting(CatalogEntry::key)
                    .doesNotContain("userAssignments");
        }
    }

    @Test
    void aHeldRoleIsOfferedUnlessTheConstraintsForbidItAgain(MidPointConfigTester t) {
        importFixture(t, null);

        // the defaults allow the same target and relation again: offered on every line
        RequestAccessPage ann = t.actor("rc-ann").requestAccess().offersRole(CRM);
        assertThat(ann.explain(CRM).heldAs()).isEqualTo("default");
        assertThat(ann.explain(CRM).addEnabled()).isTrue();
    }

    @Test
    void aHeldRoleIsShownNotOfferedWhenTheConstraintsForbidTheSameTarget(MidPointConfigTester t) {
        importFixture(t, new AssignmentConstraintsType().allowSameTarget(false).allowSameRelation(false));

        RequestAccessPage ann = t.actor("rc-ann").requestAccess().showsRole(CRM).offersNoRole(CRM).offersRole(VPN);
        assertThat(ann.offers(CRM)).isFalse();
        assertThat(ann.entry("rc-sales").offers(CRM)).isFalse();
        assertThatThrownBy(() -> ann.offersRole(CRM)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("rc-ann: the request-access page for rc-ann (relation default) shows Role"
                        + " rc-crm but does not offer it: rc-ann holds it already (default)");
    }
}
