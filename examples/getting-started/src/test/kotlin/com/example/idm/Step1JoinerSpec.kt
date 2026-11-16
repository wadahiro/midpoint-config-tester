package com.example.idm

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec

@MidPointConfigTest
class Step1JoinerSpec : MidPointConfigSpec({
    "a joiner gets a user and an LDAP account" {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("ou", "Sales")
        t.mails().to("e1001@example.com").withSubject("Welcome John Doe").single()
    }
})
