package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RichHyperlinkType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceElementVisibilityType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;
import com.github.wadahiro.midpoint.tester.GuiProfile;

/**
 * The users' GUI profiles as midPoint compiled them at login ({@link Actor#guiProfile}), over the sample deployment
 * {@code scenarios/access}: the system configuration's {@code adminGuiConfiguration} for everyone, merged with the
 * Department manager role's, which the manager of IT gets from the org's inducement.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class GuiProfileScenarioTest {

    @Test
    void everyoneHasTheSystemConfigurations(MidPointConfigTester t) {
        GuiProfile jack = t.as(Users.JACK).guiProfile();

        assertThat(jack.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::identifier)
                .containsExactly("people");
        assertThat(jack.view(UserType.COMPLEX_TYPE, "people").label()).isEqualTo("People");
        assertThat(jack.additionalMenuLinks()).extracting(RichHyperlinkType::getLabel).containsExactly("Service desk");
        assertThat(jack.defaultTimezone()).isEqualTo("UTC");
        assertThat(jack.featureVisibility("orgTreeExpandAll")).isEqualTo(UserInterfaceElementVisibilityType.VISIBLE);
    }

    /** The role's configuration after the system configuration's: its view added, its time zone, hidden wins. */
    @Test
    void theManagerHasTheInducedRolesToo(MidPointConfigTester t) {
        GuiProfile manager = t.as(Users.IT_MANAGER).guiProfile();

        assertThat(manager.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::identifier)
                .containsExactlyInAnyOrder("people", "department-members");
        assertThat(manager.view(UserType.COMPLEX_TYPE, "department-members").label()).isEqualTo("My department");
        assertThat(manager.defaultTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(manager.featureVisibility("orgTreeExpandAll")).isEqualTo(UserInterfaceElementVisibilityType.HIDDEN);
        assertThat(manager.additionalMenuLinks()).extracting(RichHyperlinkType::getLabel)
                .containsExactly("Service desk");
    }

    /**
     * Whether the side menu lists a view, by the line's rule. 4.4+: a view that is not hidden (4.8+: nor the default
     * view).
     * 4.0: also the collection's reference, resolved as the user: the End user role does not read object
     * collections, so no view is in the menu of an end user.
     */
    @Test
    void theMenuListsTheViewsByTheLinesRule(MidPointConfigTester t) {
        GuiProfile manager = t.as(Users.IT_MANAGER).guiProfile();

        assertThat(manager.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::inMenu)
                .containsOnly(!t.line().equals("4.0"));
        // why on 4.0: the manager does not get the views' collection
        assertThat(t.midpoint().as("it-manager").get(ObjectCollectionType.class, "People").value()).isNull();
        // the administrator reads everything
        assertThat(t.as("administrator").guiProfile().view(UserType.COMPLEX_TYPE, "people").inMenu()).isTrue();
    }
}
