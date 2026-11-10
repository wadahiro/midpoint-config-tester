package com.example.kotest

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.steps
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.temporal.ChronoUnit

const val NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3"

fun MidPointConfigTester.addUser(name: String) {
    midpoint().addObjectXml("<user xmlns='$NS'><name>$name</name></user>")
}

fun MidPointConfigTester.userNames(): List<String> =
    midpoint().actor("administrator").search(UserType::class.java, null).value().map { it.name.orig }

/**
 * What a container shares: its prefix runs once, each of its tests starts from the prefix's midPoint (the users, the
 * clock, the mails seen so far, the open work items), nested containers likewise; steps continue each other.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class CheckpointSpec : MidPointConfigSpec({

    var prefixRuns = 0
    lateinit var stopped: Instant
    var mailsAfterPrefix = 0

    "a root test starts from the deployment" {
        t.userNames() shouldNotContain "prefix"
    }

    "jack has asked for Privileged Access, the clock stopped 10 days on" - {
        prefixRuns++
        t.addUser("prefix")
        stopped = t.clock().now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        t.clock().stopAt(stopped)
        t.actor("jack").request().assign("Privileged Access")
        mailsAfterPrefix = t.mails().count()

        "the owner approves, 30 days later" {
            t.clock().advance("P30D")
            t.workItems().assignedTo("role-owner").single().approve(t.actor("role-owner"), "fine by me")
            t.addUser("approval")
            (t.mails().count() > mailsAfterPrefix) shouldBe true
        }

        "the next test starts from the prefix again" {
            prefixRuns shouldBe 1
            t.userNames() shouldContain "prefix"
            t.userNames() shouldNotContain "approval"
            t.clock().now() shouldBe stopped
            t.mails().count() shouldBe mailsAfterPrefix
            t.workItems().assignedTo("role-owner").single()
        }

        "a nested container" - {
            t.addUser("nested")

            "has its own prefix on the outer one" {
                t.userNames() shouldContain "prefix"
                t.userNames() shouldContain "nested"
                t.addUser("inner-1")
            }

            "and gives it to each of its tests" {
                t.userNames() shouldContain "nested"
                t.userNames() shouldNotContain "inner-1"
            }
        }

        "after the nested container, its prefix is gone too" {
            t.userNames() shouldNotContain "nested"
            t.userNames() shouldContain "prefix"
        }

        steps("steps continue each other") {
            "the first adds a user" { t.addUser("step-1") }
            "the second sees it" { t.userNames() shouldContain "step-1" }
        }

        "after the steps, their work is gone" {
            t.userNames() shouldNotContain "step-1"
        }
    }

    "the next root starts from the deployment again" {
        t.userNames() shouldNotContain "prefix"
        t.mails().count() shouldBe 0
    }
})
