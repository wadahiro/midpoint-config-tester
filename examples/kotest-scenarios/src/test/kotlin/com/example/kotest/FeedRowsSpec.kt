package com.example.kotest

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import io.kotest.datatest.withData

data class Row(val lastName: String, val department: String, val familyName: String, val account: Boolean)

/**
 * Rows of a source feed as tests: `withData` in a container makes one test per row, each starting from the
 * container's prefix (the HR feed with one employee), and each row claims the configuration at rest after it.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class FeedRowsSpec : MidPointConfigSpec({

    "Ann is in the HR feed" - {
        t.hire("1000", "Ann", "Early", "Sales")

        precondition("Ann has her LDAP account") { t.user("e1000").hasAccountOn("LDAP") }

        withData(
            nameFn = { "last name '${it.lastName}', department '${it.department}'" },
            Row(" DOE ", "Sales", "Doe", true),             // blanks and case as the HR export has them
            Row("MÜLLER", "IT", "Müller", true),            // not ASCII
            Row("Doe", "", "Doe", false),                   // no department: no role, no account
        ) { row ->
            t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to row.lastName,
                    "department" to row.department, "startDate" to "2020-04-01"))
            val hired = t.task("Reconcile HR").run()

            t.user("e1001").hasItem("familyName", row.familyName)
            if (row.account) t.user("e1001").hasAccountOn("LDAP") else t.user("e1001").hasNoAccountOn("LDAP")
            hired.changes().focuses().forEach { it.recompute().none() }
        }
    }
})
