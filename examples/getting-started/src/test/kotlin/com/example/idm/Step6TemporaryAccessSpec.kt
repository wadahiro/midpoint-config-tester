package com.example.idm

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition

@MidPointConfigTest
class Step6TemporaryAccessSpec : MidPointConfigSpec({
    "John has VPN Access for 30 days" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()
        val end = t.clock().today().plusDays(30)
        t.actor("e1001").request().assign("VPN Access") { it.validTo(end) }   // the assignment's validTo
        t.workItems().assignedTo("alice").single().approve(t.actor("alice"))

        precondition("his LDAP account has the VPN") {
            t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("businessCategory", "vpn")
        }

        "on the 30th day the VPN goes from his LDAP account, which his department keeps" {
            t.clock().advanceTo(end).advance("PT1H").runDue()
            t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasNoAttribute("businessCategory")
            t.user("e1001").hasAccountOn("LDAP")
        }
    }
})
