package com.example.kotest

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.MidPointConfigTestLifecycle
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

const val JOHN = "uid=e1001,ou=people,dc=example,dc=com"

fun MidPointConfigTester.hire(number: String, firstName: String, lastName: String, department: String) {
    source("HR").add(number, mapOf("firstName" to firstName, "lastName" to lastName, "department" to department,
            "startDate" to "2020-04-01"))
    task("Reconcile HR").run()
}

/**
 * A failed Kotest matcher in a spec goes through the explanation of JUnit's extension (MidPointConfigSpec reports it
 * so): the mappings behind the values the test read, in a container's test too.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class ExplanationSpec : MidPointConfigSpec({

    "John is hired in Sales" - {
        t.hire("1001", "John", "Doe", "Sales")

        "a failure on his LDAP account explains the ou" {
            val failure = shouldThrow<AssertionError> { t.target("LDAP").account(JOHN).value("ou") shouldBe "Marketing" }
            val explained = MidPointConfigTestLifecycle.explained(t, failure)
            explained.message!! shouldContain "Marketing"
            explained.message!! shouldContain "Mappings that set ou of $JOHN on LDAP in this test"
        }
    }
})
