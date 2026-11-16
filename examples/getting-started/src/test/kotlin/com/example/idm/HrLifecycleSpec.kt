package com.example.idm

import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import com.github.wadahiro.midpoint.configtester.kotest.verifyAll
import io.kotest.datatest.withData

@MidPointConfigTest
class HrLifecycleSpec : MidPointConfigSpec({
    "John joins Sales" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        precondition("he is in Sales, with an account on LDAP") {
            t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        }

        "moving to IT changes his role and his LDAP account's ou, nothing else" {
            t.source("HR").modify("1001", "department", "IT")
            t.task("Reconcile HR").run().verifyAll {
                changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef")
                connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou")
            }
        }

        withData(nameFn = { "moving to ${it.first}" }, "IT" to true, "Finance" to false) { (department, known) ->
            t.source("HR").modify("1001", "department", department)
            t.task("Reconcile HR").run()
            if (known) t.user("e1001").hasAssignment("Dept $department").hasAccountOn("LDAP")
            else t.user("e1001").hasNoAssignment("Dept Sales").hasNoAccountOn("LDAP")   // no role for Finance
        }

        "John requests VPN Access" - {
            t.actor("e1001").request().assign("VPN Access")

            precondition("the request waits for alice") {
                t.workItems().assignedTo("alice").single()
                t.user("e1001").hasNoAssignment("VPN Access")
            }

            "alice approves: his LDAP account gets the VPN" {
                t.workItems().assignedTo("alice").single().approve(t.actor("alice")).verifyAll {
                    changes().of("e1001").single().only("assignment", "roleMembershipRef")
                    connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single()
                        .only("businessCategory")
                    changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                    changes().ofType(TaskType::class.java).added().single()   // the task that executes the approved change
                }
                t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isApproved()
            }

            "alice rejects: John and his LDAP account stay as they were" {
                t.workItems().assignedTo("alice").single().reject(t.actor("alice")).verifyAll {
                    changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                    changes().of("e1001").none()                        // John as he was
                    connectorCalls().to("LDAP").writes().none()         // his LDAP account as it was
                }
                t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected()
                t.workItems().assignedTo("alice").none()
            }
        }
    }
})
