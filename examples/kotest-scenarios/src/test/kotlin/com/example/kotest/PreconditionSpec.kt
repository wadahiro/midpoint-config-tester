package com.example.kotest

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec
import com.github.wadahiro.midpoint.configtester.kotest.precondition
import com.github.wadahiro.midpoint.configtester.kotest.steps
import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.engine.test.TestResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.reflect.KClass

/**
 * The precondition of a container whose body changes midPoint ([MidPointConfigSpec]): the specs below run through
 * Kotest's launcher, and their results are checked here; they run only so ([Launched]).
 */
class PreconditionSpec : FunSpec({

    test("a body that changes midPoint needs a precondition as its first test") {
        val results = launch(MissingPrecondition::class)
        results.error("the first test").message!! shouldContain "is no precondition"
        results.error("the second test").message!! shouldContain "Not run"
    }

    test("a precondition that fails fails the other tests without running them, nested ones by name") {
        val results = launch(FailingPrecondition::class)
        results.of("precondition: the user is there").shouldBeInstanceOf<TestResult.Failure>()
        results.failure("a test").message!! shouldContain "Not run: the precondition of"
        results.failure("a nested test").message!! shouldContain "Not run: the precondition of"
        Ran.tests shouldBe emptyList()
    }

    test("a precondition holds: the tests run, from the body's state") {
        val results = launch(HoldingPrecondition::class)
        results.of("precondition: the user is there").isSuccess shouldBe true
        results.of("a test").isSuccess shouldBe true
        results.of("another test").isSuccess shouldBe true
    }

    test("a precondition that is not the first test fails") {
        val results = launch(LatePrecondition::class)
        results.error("precondition: late").message!! shouldContain "is not the first test"
    }

    test("a precondition that changes midPoint fails, and the tests after it do not run") {
        val results = launch(WritingPrecondition::class)
        results.error("precondition: writes").message!! shouldContain "changed midPoint"
        results.error("a test").message!! shouldContain "Not run"
    }

    test("a body that only reads needs no precondition") {
        val results = launch(ReadingBody::class)
        results.of("a test").isSuccess shouldBe true
    }

    test("moving the clock in a body is a change of midPoint") {
        val results = launch(ClockInBody::class)
        results.error("a test").message!! shouldContain "is no precondition"
    }

    test("moving the clock between the tests of a container fails the next test") {
        val results = launch(ClockBetweenTests::class)
        results.of("precondition: the user is there").isSuccess shouldBe true
        results.error("the second test").message!! shouldContain "between its tests"
    }

    test("steps: the first step of a body that changes midPoint is the precondition") {
        val results = launch(StepsWithPrecondition::class)
        results.of("precondition: the user is there").isSuccess shouldBe true
        results.of("a step").isSuccess shouldBe true
        Ran.tests shouldContain "a step"
    }
})

/** Lets the specs below run: only from [PreconditionSpec]. */
class Launched : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean = System.getProperty(PROPERTY) == "true"

    companion object {
        const val PROPERTY = "example.precondition.launched"
    }
}

/** The tests of the launched specs that ran. */
object Ran {
    val tests = mutableListOf<String>()
}

private suspend fun launch(spec: KClass<out Spec>): CollectingTestEngineListener {
    val listener = CollectingTestEngineListener()
    Ran.tests.clear()
    System.setProperty(Launched.PROPERTY, "true")
    try {
        TestEngineLauncher().withoutEnvFilters().withListener(listener).withSpecRefs(SpecRef.Reference(spec)).execute()
    } finally {
        System.clearProperty(Launched.PROPERTY)
    }
    return listener
}

private fun CollectingTestEngineListener.of(name: String): TestResult =
    tests.entries.single { it.key.name.name == name }.value

private fun CollectingTestEngineListener.error(name: String): Throwable =
    of(name).shouldBeInstanceOf<TestResult.Error>().cause

private fun CollectingTestEngineListener.failure(name: String): AssertionError =
    of(name).shouldBeInstanceOf<TestResult.Failure>().cause

private const val ACCESS = "classpath:scenarios/access/midpoint-config-tester.yaml"

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class MissingPrecondition : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("missing")
        "the first test" { t.userNames() shouldContain "missing" }
        "the second test" { t.userNames() shouldContain "missing" }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class FailingPrecondition : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("failing")
        precondition("the user is there") { t.userNames() shouldContain "someone else" }
        "a test" { Ran.tests += "a test" }
        "a nested container" - {
            "a nested test" { Ran.tests += "a nested test" }
        }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class HoldingPrecondition : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("holding")
        precondition("the user is there") { t.userNames() shouldContain "holding" }
        "a test" {
            t.userNames() shouldContain "holding"
            t.addUser("holding-2")
        }
        "another test" { t.userNames().contains("holding-2") shouldBe false }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class LatePrecondition : MidPointConfigSpec({
    "a container that only reads" - {
        "a test" { t.userNames() }
        precondition("late") { t.userNames() }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class WritingPrecondition : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("writing")
        precondition("writes") { t.addUser("writing-2") }
        "a test" { Ran.tests += "a test" }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class ReadingBody : MidPointConfigSpec({
    "a container that only reads" - {
        val names = t.userNames()
        "a test" { t.userNames() shouldBe names }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class ClockInBody : MidPointConfigSpec({
    "a day later" - {
        t.clock().advance("P1D")
        "a test" { }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class ClockBetweenTests : MidPointConfigSpec({
    "a user is added" - {
        t.addUser("between")
        precondition("the user is there") { t.userNames() shouldContain "between" }
        t.clock().advance("P1D")
        "the second test" { }
    }
})

@EnabledIf(Launched::class)
@MidPointConfigTest(ACCESS)
class StepsWithPrecondition : MidPointConfigSpec({
    steps("a user is added") {
        t.addUser("steps")
        precondition("the user is there") { t.userNames() shouldContain "steps" }
        "a step" { Ran.tests += "a step" }
    }
})
