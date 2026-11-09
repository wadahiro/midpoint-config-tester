package com.github.wadahiro.midpoint.configtester.kotest

import com.github.wadahiro.midpoint.configtester.MidPointConfigTestLifecycle
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester
import com.github.wadahiro.midpoint.tester.internal.Checkpoint
import com.github.wadahiro.midpoint.tester.internal.Deployment
import io.kotest.core.Tag
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FreeSpec
import io.kotest.core.spec.style.scopes.FreeSpecContainerScope
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.engine.test.TestResult

/**
 * A Kotest spec over the deployment of its `@MidPointConfigTest` manifest (the annotation of JUnit test classes, read
 * the same way): loaded once per JVM and manifest, midPoint reset to it before every root, `t` the tester of the
 * running root.
 *
 * A container shares its prefix: the code before its first test runs once, and every test of the container (a leaf
 * or a nested container) starts from midPoint as the prefix left it, through a checkpoint ([Checkpoint]: the
 * database, the targets, the files, the clock, the mails seen so far). What one test does is gone for the next:
 *
 * ```
 * @MidPointConfigTest("classpath:...")
 * class CampaignSpec : MidPointConfigSpec({
 *     "the campaign has started" - {
 *         val campaign = t.startCampaign()          // once
 *         "a reminder before the deadline" { ... }  // from the started campaign
 *         "a revocation" - {
 *             t.decide(campaign, "revoke")          // once, from the started campaign
 *             "the campaign closes" { ... }
 *             "the report lists it" { ... }         // from the revocation, not after the closing
 *         }
 *     }
 * })
 * ```
 *
 * Work in a container's body between its tests would run on the state after a test's restore and be lost for the
 * tests after the next one: it fails the next test. `steps` is the other kind of container: its tests run in order
 * on one state, each continuing the last (a scenario's steps), stopping at the first failure.
 */
abstract class MidPointConfigSpec(body: MidPointConfigSpec.() -> Unit = {}) : FreeSpec() {

    /** The tag of the `steps` containers. */
    object Steps : Tag()

    private var deployment: Deployment? = null
    private var current: MidPointConfigTester? = null
    /** The checkpoint of each running container that shares its prefix, by its test case. */
    private val frames = HashMap<TestCase, Frame>()

    private class Frame {
        var checkpoint: Checkpoint? = null
        /** midPoint's activity right after the last restore: the container's body must not add to it. */
        var activityAfterRestore: Long? = null
    }

    /** The tester of the running root. */
    val t: MidPointConfigTester
        get() = current ?: throw IllegalStateException("The tester exists only while a test of the spec runs")

    init {
        // one spec instance, one midPoint per JVM: the tests in order, the checkpoints give each its start
        isolationMode = IsolationMode.SingleInstance
        testExecutionMode = TestExecutionMode.Sequential
        beforeSpec { deployment = MidPointConfigTestLifecycle.load(it::class.java) }
        extension(object : TestCaseExtension {
            override suspend fun intercept(testCase: TestCase, execute: suspend (TestCase) -> TestResult): TestResult =
                run(testCase, execute)
        })
        body()
    }

    private suspend fun run(testCase: TestCase, execute: suspend (TestCase) -> TestResult): TestResult {
        val parent = testCase.parent
        if (parent == null) {
            val loaded = deployment ?: throw IllegalStateException("No deployment loaded for ${this::class.java.name}")
            val tester = MidPointConfigTestLifecycle.begin(loaded)
            current = tester
            try {
                return explained(tester, runContainer(testCase, execute))
            } finally {
                current = null
                MidPointConfigTestLifecycle.end()
            }
        }
        val tester = t
        MidPointConfigTestLifecycle.bind(tester)
        val frame = frames[parent]
            ?: return explained(tester, runContainer(testCase, execute)) // a test of steps: on the state as it is
        val checkpoint = frame.checkpoint
        if (checkpoint == null) {
            frame.checkpoint = MidPointConfigTestLifecycle.checkpoint(tester)
        } else if (frame.activityAfterRestore != MidPointConfigTestLifecycle.activity(tester)) {
            throw IllegalStateException("The container '${parent.name.name}' worked on midPoint between its tests"
                    + " (before '${testCase.name.name}'): each test starts from the container's prefix, and that work"
                    + " would be undone after the next test. Do it before the container's first test, or in a container"
                    + " of its own, or use steps for tests that continue each other")
        }
        try {
            return explained(tester, runContainer(testCase, execute))
        } finally {
            MidPointConfigTestLifecycle.restore(tester, frame.checkpoint!!)
            MidPointConfigTestLifecycle.bind(tester)
            frame.activityAfterRestore = MidPointConfigTestLifecycle.activity(tester)
        }
    }

    /** Runs the test; a container that shares its prefix gets its frame, released when it ends. */
    private suspend fun runContainer(testCase: TestCase, execute: suspend (TestCase) -> TestResult): TestResult {
        if (testCase.type != TestType.Container || testCase.config?.tags?.contains(Steps) == true) {
            return execute(testCase)
        }
        val frame = Frame()
        frames[testCase] = frame
        try {
            return execute(testCase)
        } finally {
            frames.remove(testCase)
            frame.checkpoint?.let { MidPointConfigTestLifecycle.release(t, it) }
        }
    }

    private fun explained(tester: MidPointConfigTester, result: TestResult): TestResult =
        if (result is TestResult.Failure) {
            val cause = MidPointConfigTestLifecycle.explained(tester, result.cause)
            if (cause is AssertionError && cause !== result.cause) result.copy(cause = cause) else result
        } else {
            result
        }

    /** A container whose tests run in order on one state, each continuing the last; stops at the first failure. */
    fun steps(name: String, test: suspend FreeSpecContainerScope.() -> Unit) =
        name.config(failfast = true, tags = setOf(Steps)) - test
}

/** [MidPointConfigSpec.steps] in a container. */
suspend fun FreeSpecContainerScope.steps(name: String, test: suspend FreeSpecContainerScope.() -> Unit) =
    name.config(failfast = true, tags = setOf(MidPointConfigSpec.Steps)) - test
