package com.example.kotest

import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.verifyAll

/**
 * A verb that makes midPoint work returns what midPoint did: a test claims what midPoint derived from it, not what
 * the test passed in.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class VerbEffectsSpec : MidPointConfigSpec({

    "a recompute of a user as midPoint keeps them changes nothing" {
        t.user("jack").recompute().none()
    }

    "an assignment without approvers changes the user and nothing else" {
        t.user("helpdesk").assign("Accountant").verifyAll {
            changes().of("helpdesk").single().only("assignment", "roleMembershipRef")
        }
    }

    "a request of a role with approvers opens a case and leaves the user as they were" {
        t.actor("jack").request().assign("Privileged Access").verifyAll {
            changes().ofType(CaseType::class.java).added().exactly(2)            // the request and its approval
            mails().to("role-owner@example.com").single()                // the work item
            mails().to("jack@example.com").withSubject("Request submitted").single()
        }
        t.user("jack").hasNoAssignment("Privileged Access")
    }
})
