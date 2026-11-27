package com.example.kotest

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import com.github.wadahiro.midpoint.configtester.kotest.steps
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.engine.test.TestResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `tester.only` ([MidPointConfigSpec]): the spec below runs through Kotest's launcher with the property set, and which
 * of its tests ran is checked here.
 */
class OnlySpec : FunSpec({

    test("only the listed IDs run, with the containers' bodies and preconditions") {
        val results = launchOnly("CASE-012,CASE-020")
        results.of("precondition: the user is there").isSuccess shouldBe true
        results.of("CASE-012: selected").isSuccess shouldBe true
        results.of("CASE-01: a shorter ID is not selected by CASE-012").shouldBeInstanceOf<TestResult.Ignored>()
        results.of("CASE-013: not listed").shouldBeInstanceOf<TestResult.Ignored>()
        results.of("no ID").shouldBeInstanceOf<TestResult.Ignored>()
        results.of("a nested test").isSuccess shouldBe true
        results.of("a step").isSuccess shouldBe true
        Ran.tests shouldContainExactly listOf("CASE-012", "a nested test", "a step")
    }

    test("an ID ending with * is a plain prefix") {
        val results = launchOnly("CASE-01*")
        results.of("CASE-012: selected").isSuccess shouldBe true
        results.of("CASE-01: a shorter ID is not selected by CASE-012").isSuccess shouldBe true
        results.of("CASE-013: not listed").isSuccess shouldBe true
        results.of("no ID").shouldBeInstanceOf<TestResult.Ignored>()
        results.of("a nested test").shouldBeInstanceOf<TestResult.Ignored>()
        Ran.tests shouldContainExactly listOf("CASE-012", "CASE-01", "CASE-013", "a step")
    }

    test("without the property every test runs") {
        val results = launchOnly(null)
        results.tests.values.all { it.isSuccess } shouldBe true
        Ran.tests shouldContainExactly listOf("CASE-012", "CASE-01", "CASE-013", "no ID", "a nested test", "a step")
    }
})

private suspend fun launchOnly(only: String?): CollectingTestEngineListener {
    val listener = CollectingTestEngineListener()
    Ran.tests.clear()
    System.setProperty(Launched.PROPERTY, "true")
    if (only != null) System.setProperty("tester.only", only)
    try {
        TestEngineLauncher().withoutEnvFilters().withListener(listener).withSpecRefs(SpecRef.Reference(OnlyTests::class))
            .execute()
    } finally {
        System.clearProperty(Launched.PROPERTY)
        System.clearProperty("tester.only")
    }
    return listener
}

private fun CollectingTestEngineListener.of(name: String): TestResult =
    tests.entries.single { it.key.name.name == name }.value

@EnabledIf(Launched::class)
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class OnlyTests : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("only")
        precondition("the user is there") { t.userNames().contains("only") shouldBe true }
        "CASE-012: selected" { Ran.tests += "CASE-012" }
        "CASE-01: a shorter ID is not selected by CASE-012" { Ran.tests += "CASE-01" }
        "CASE-013: not listed" { Ran.tests += "CASE-013" }
        "no ID" { Ran.tests += "no ID" }
    }
    "CASE-020: a container selects its tests" - {
        "a nested test" { Ran.tests += "a nested test" }
    }
    steps("CASE-030: steps run whole") {
        "a step" { Ran.tests += "a step" }
    }
})
