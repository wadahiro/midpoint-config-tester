package com.example.idm

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition

@MidPointConfigTest
class Step3DatesSpec : MidPointConfigSpec({
    "Kate joins in 10 days" - {
        val start = t.clock().today().plusDays(10)
        t.source("HR").add("1003", mapOf("firstName" to "Kate", "lastName" to "Smith", "department" to "IT",
                "startDate" to start.toString()))
        t.task("Reconcile HR").run()

        precondition("before her start date she has no role and no account") {
            t.user("e1003").isDisabled().hasNoAssignment("Dept IT").hasNoAccountOn("LDAP")
        }

        "on her start date she gets her role and her LDAP account" {
            t.clock().advanceTo(start).advance("PT1H").runDue()   // the scanners and the due tasks at the new time
            t.user("e1003").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP")
        }
    }

    "John leaves in 5 days" - {
        val end = t.clock().today().plusDays(5)
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01", "endDate" to end.toString()))
        t.task("Reconcile HR").run()

        precondition("until then he is enabled") { t.user("e1001").isEnabled() }

        "on his end date, his user and his LDAP account are disabled" {
            t.clock().advanceTo(end).advance("PT1H").runDue()
            t.user("e1001").isDisabled()
            t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").isDisabled()
        }
    }
})
