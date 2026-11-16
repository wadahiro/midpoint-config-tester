package com.example.idm

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import com.github.wadahiro.midpoint.configtester.kotest.verifyAll
import io.kotest.datatest.withData

@MidPointConfigTest
class Step2MoverSpec : MidPointConfigSpec({
    "John has joined Sales" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        precondition("he is in Sales, with an account on LDAP") {
            t.user("e1001").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        }

        "moving to IT changes his role and his LDAP account's ou, nothing else" {
            t.source("HR").modify("1001", "department", "IT")
            t.task("Reconcile HR").run().verifyAll {
                changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef")
                connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou")
            }
        }

        "reconciling again changes nothing" {
            t.task("Reconcile HR").run().none()
        }

        withData(nameFn = { "moving to ${it.first}" }, "IT" to true, "Finance" to false) { (department, known) ->
            t.source("HR").modify("1001", "department", department)
            t.task("Reconcile HR").run()
            if (known) t.user("e1001").hasAssignment("Dept $department").hasAccountOn("LDAP")
            else t.user("e1001").hasNoAssignment("Dept Sales").hasNoAccountOn("LDAP")   // no role for Finance
        }
    }
})
