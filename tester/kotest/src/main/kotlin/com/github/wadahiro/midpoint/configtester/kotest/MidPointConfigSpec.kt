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
import io.kotest.core.test.TestScope
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
 *
 * A container whose body changes midPoint (a driver call, a raw write, a target system, a file, the clock...) checks
 * what it made in its first test, a [precondition]: the test only reads, and when it fails, the container's other tests
 * fail without running (they are not skipped), each by its name. A first test that is no precondition after such a
 * body fails, as a precondition that is not the first test or that changes midPoint:
 *
 * ```
 * "jdoe has the legacy token" - {
 *     t.raw().user("jdoe").set("extension/token", "legacy-1")
 *     precondition("the token is there") { t.user("jdoe").hasItem("extension/token", "legacy-1") }
 *     "recomputing keeps it" { t.user("jdoe").recompute().none() }
 * }
 * ```
 *
 * `tester.only` (or `TESTER_ONLY`) runs only the tests whose name, or a container's around them, starts with one of the
 * listed IDs ([Only]); the containers' bodies, the preconditions and the tests of `steps` still run.
 */
abstract class MidPointConfigSpec(body: MidPointConfigSpec.() -> Unit = {}) : FreeSpec() {

    /** The tag of the `steps` containers. */
    object Steps : Tag()

    /** The tag of the [precondition] tests. */
    object Precondition : Tag()

    private var deployment: Deployment? = null
    private var current: MidPointConfigTester? = null
    /** What each running container keeps between its tests, by its test case. */
    private val frames = HashMap<TestCase, Frame>()

    /**
     * A running container: midPoint's activity when its body started, its checkpoint (not for steps), and why its
     * tests do not run (its precondition failed or was missing, or the container's one did).
     */
    private class Frame(val activityAtStart: Long, val steps: Boolean, var failure: Throwable?) {
        var started = false
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
        if (testCase.type != TestType.Container && testCase.config?.tags?.contains(Precondition) != true
                && (parent == null || frames[parent]?.steps != true) && !Only.selects(testCase)) {
            return TestResult.Ignored("not in ${Only.PROPERTY}")
        }
        if (parent == null) {
            val loaded = deployment ?: throw IllegalStateException("No deployment loaded for ${this::class.java.name}")
            val tester = MidPointConfigTestLifecycle.begin(loaded)
            current = tester
            try {
                return explained(tester, runContainer(testCase, execute, null))
            } finally {
                current = null
                MidPointConfigTestLifecycle.end()
            }
        }
        val tester = t
        MidPointConfigTestLifecycle.bind(tester)
        val frame = frames[parent] ?: return explained(tester, runContainer(testCase, execute, null))
        frame.failure?.let { return notRun(testCase, execute, parent, it) }
        val first = !frame.started
        frame.started = true
        val precondition = testCase.config?.tags?.contains(Precondition) == true
        val misuse = when {
            first && !precondition && MidPointConfigTestLifecycle.activity(tester) != frame.activityAtStart ->
                "The container '${parent.name.name}' changed midPoint in its body, and its first test" +
                        " '${testCase.name.name}' is no precondition: check what the body made with" +
                        " precondition(\"...\") { } as the container's first test, before the tests that depend on it"
            !first && precondition ->
                "The precondition '${testCase.name.name}' is not the first test of '${parent.name.name}': write it" +
                        " right after the container's body, before the tests that depend on it"
            else -> null
        }
        if (misuse != null) {
            val failure = IllegalStateException(misuse)
            frame.failure = failure
            return if (testCase.type == TestType.Container) notRun(testCase, execute, parent, failure)
            else failing(testCase, execute, failure)
        }
        if (!frame.steps) {
            if (frame.checkpoint == null) {
                frame.checkpoint = MidPointConfigTestLifecycle.checkpoint(tester)
            } else if (frame.activityAfterRestore != MidPointConfigTestLifecycle.activity(tester)) {
                return failing(testCase, execute, IllegalStateException("The container '${parent.name.name}'"
                        + " worked on midPoint between its tests (before '${testCase.name.name}'): each test starts"
                        + " from the container's prefix, and that work would be undone after the next test. Do it"
                        + " before the container's first test, or in a container of its own, or use steps for tests"
                        + " that continue each other"))
            }
        }
        val before = MidPointConfigTestLifecycle.activity(tester)
        try {
            val result = explained(tester, runContainer(testCase, execute, null))
            if (!precondition) {
                return result
            }
            if (MidPointConfigTestLifecycle.activity(tester) != before) {
                val failure = IllegalStateException("The precondition '${testCase.name.name}' of" +
                        " '${parent.name.name}' changed midPoint: a precondition only reads what the container's" +
                        " body made; change midPoint in the body")
                frame.failure = failure
                return TestResult.Error(result.duration, failure)
            }
            frame.failure = result.errorOrNull
            return result
        } finally {
            frame.checkpoint?.let {
                MidPointConfigTestLifecycle.restore(tester, it)
                MidPointConfigTestLifecycle.bind(tester)
                frame.activityAfterRestore = MidPointConfigTestLifecycle.activity(tester)
            }
        }
    }

    /**
     * A test of a container whose precondition failed or was missing: a leaf fails without running; a container runs
     * its body, so that its tests are known, and each of them fails so.
     */
    private suspend fun notRun(testCase: TestCase, execute: suspend (TestCase) -> TestResult, parent: TestCase,
            cause: Throwable): TestResult {
        if (testCase.type == TestType.Container) {
            return runContainer(testCase, execute, cause)
        }
        val message = "Not run: the precondition of '${parent.name.name}' failed or is missing"
        return failing(testCase, execute,
                if (cause is AssertionError) AssertionError(message, cause) else IllegalStateException(message, cause))
    }

    /** The test, run with a body that throws the error instead of its own (Kotest reports it as any other test). */
    private suspend fun failing(testCase: TestCase, execute: suspend (TestCase) -> TestResult, error: Throwable) =
        execute(testCase.copy(test = { throw error }))

    /**
     * Runs the test; a container gets its frame, released when it ends. A container of a container whose precondition
     * did not hold gets the failure: its tests do not run.
     */
    private suspend fun runContainer(testCase: TestCase, execute: suspend (TestCase) -> TestResult,
            failure: Throwable?): TestResult {
        if (testCase.type != TestType.Container) {
            return execute(testCase)
        }
        val frame = Frame(MidPointConfigTestLifecycle.activity(t), testCase.config?.tags?.contains(Steps) == true,
                failure)
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

/**
 * `tester.only` (or the environment variable `TESTER_ONLY`): IDs, comma-separated, such as `CASE-012,CASE-015`. A test
 * runs when its name or the name of a container around it starts with one of them, followed by no letter or digit
 * (`CASE-01` does not select `CASE-012`). An ID ending with `*` is a plain prefix: `CASE-01*` selects `CASE-010` to
 * `CASE-019` (and `CASE-01` itself). Unset or blank: every test runs.
 */
internal object Only {
    const val PROPERTY = "tester.only"

    private fun ids(): List<String> =
        (System.getProperty(PROPERTY) ?: System.getenv("TESTER_ONLY") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun selects(testCase: TestCase): Boolean {
        val ids = ids()
        if (ids.isEmpty()) {
            return true
        }
        var test: TestCase? = testCase
        while (test != null) {
            val name = test.name.name
            if (ids.any { matches(name, it) }) {
                return true
            }
            test = test.parent
        }
        return false
    }

    private fun matches(name: String, id: String): Boolean =
        if (id.endsWith("*")) name.startsWith(id.dropLast(1))
        else name.startsWith(id) && name.getOrNull(id.length)?.isLetterOrDigit() != true
}

/**
 * The container's first test, which checks what the container's body made before the tests that depend on it: it only
 * reads midPoint. Required after a body that changes midPoint ([MidPointConfigSpec]); when it fails, the container's
 * other tests fail without running.
 */
suspend fun FreeSpecContainerScope.precondition(name: String, test: suspend TestScope.() -> Unit) =
    "precondition: $name".config(tags = setOf(MidPointConfigSpec.Precondition), test = test)

/** [MidPointConfigSpec.steps] in a container. */
suspend fun FreeSpecContainerScope.steps(name: String, test: suspend FreeSpecContainerScope.() -> Unit) =
    name.config(failfast = true, tags = setOf(MidPointConfigSpec.Steps)) - test
