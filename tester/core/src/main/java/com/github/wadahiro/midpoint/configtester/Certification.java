package com.github.wadahiro.midpoint.configtester;

/**
 * A certification definition of the deployment, by name ({@link MidPointConfigTester#certification}).
 *
 * <pre>{@code
 * Campaign c = t.certification("Role assignments").start();
 * c.reviews().forUser("jack").forRole("Auditor").single().revoke(t.actor("sec-officer"), "not needed");
 * c.closeStage().remediate();
 * t.user("jack").hasNoRole("Auditor");
 * }</pre>
 */
public final class Certification {

    private final MidPointConfigTester tester;
    private final String definition;

    Certification(MidPointConfigTester tester, String definition) {
        this.tester = tester;
        this.definition = definition;
    }

    /**
     * Creates a campaign of the definition and opens its first stage, as the GUI's "Create campaign" and "Start
     * campaign": the cases are made from the scope and the reviewers of stage 1 get their work items.
     */
    public Campaign start() {
        Campaign campaign = create();
        return campaign.nextStage();
    }

    /** Creates a campaign of the definition, not started yet ({@link Campaign#nextStage()} starts it). */
    public Campaign create() {
        String oid = tester.deployment().api().certification().createCampaign(definition).oid();
        return new Campaign(tester, oid);
    }

    public String definition() {
        return definition;
    }
}
