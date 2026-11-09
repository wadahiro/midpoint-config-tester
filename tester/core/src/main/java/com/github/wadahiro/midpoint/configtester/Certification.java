package com.github.wadahiro.midpoint.configtester;

/**
 * A certification definition of the deployment, by name ({@link Scenario#certification}).
 *
 * <pre>{@code
 * Campaign c = s.certification("Role assignments").start();
 * c.reviews().forUser("jack").forRole("Auditor").single().revoke(s.as("sec-officer"), "not needed");
 * c.closeStage().remediate();
 * s.user("jack").hasNoRole("Auditor");
 * }</pre>
 */
public final class Certification {

    private final Scenario scenario;
    private final String definition;

    Certification(Scenario scenario, String definition) {
        this.scenario = scenario;
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
        String oid = scenario.deployment().api().certification().createCampaign(definition).oid();
        return new Campaign(scenario, oid);
    }

    public String definition() {
        return definition;
    }
}
