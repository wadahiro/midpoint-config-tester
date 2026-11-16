package com.example.idm

import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import com.github.wadahiro.midpoint.configtester.kotest.verifyAll

@MidPointConfigTest
class Step5DeadlineSpec : MidPointConfigSpec({
    "John requests VPN Access" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()
        t.actor("e1001").request().assign("VPN Access")

        precondition("the request waits for alice") { t.workItems().assignedTo("alice").single() }

        "2 days on, the request still waits" {
            t.clock().advance("P2D").runTriggerScanner().none()
            t.workItems().assignedTo("alice").single()
        }

        "undecided for 3 days, the request is rejected" {
            t.clock().advance("P3D").runTriggerScanner().verifyAll {
                changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                changes().of("e1001").none()
                connectorCalls().to("LDAP").writes().none()
                mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single()
            }
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected()
        }
    }
})
