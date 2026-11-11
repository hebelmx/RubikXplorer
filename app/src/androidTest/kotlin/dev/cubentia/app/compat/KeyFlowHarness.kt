package dev.cubentia.app.compat

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.cubentia.app.MainActivity
import dev.cubentia.app.di.SolverModule
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.ui.freecube.MoveKeys
import dev.cubentia.app.ui.shell.WindowWidthClass
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.SessionState
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.tutor.Tutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlin.math.roundToInt
import kotlin.math.sign

typealias KeyFlowRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * Drives the real [MainActivity] through a Key Flow and, at each surface, rotates portrait →
 * landscape → portrait through `Activity.requestedOrientation` (never device settings), asserting
 * that the session ([SessionHost.state]) and the visible cube and step are unchanged and that the
 * layout follows the current window class (Story 12.2, AC3). Every check is logged under [TAG] so the
 * host script can keep the per-surface record as evidence.
 */
class KeyFlowHarness(
    private val rule: KeyFlowRule,
    private val flow: String,
) {
    /** The app's one `@Singleton` session owner, read from the activity Hilt injected. */
    val host: SessionHost get() = activity().sessionHost

    private var surfaces = 0
    private val failures = mutableListOf<String>()

    fun activity(): MainActivity {
        var current: MainActivity? = null
        rule.activityRule.scenario.onActivity { current = it }
        return checkNotNull(current) { "No MainActivity" }
    }

    fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun enabled(tag: String): Boolean =
        rule
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null } == true

    fun awaitTag(
        tag: String,
        timeoutMs: Long = TIMEOUT_MS,
        exact: Boolean = true,
    ) {
        try {
            rule.waitUntil(timeoutMs) {
                if (exact) exists(tag) else rule.onAllNodes(tagPrefix(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: Throwable) {
            fail("$flow: \"$tag\" never appeared")
        }
    }

    fun await(
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (_: Throwable) {
            fail("$flow: timed out waiting for $what")
        }
    }

    /** The test tag of the first node whose tag starts with [prefix] (e.g. a Library tile). */
    fun firstTagStartingWith(prefix: String): String {
        awaitTag(prefix, exact = false)
        return rule
            .onAllNodes(tagPrefix(prefix), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .config[SemanticsProperties.TestTag]
    }

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("test tag starting with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    fun click(tag: String) {
        awaitTag(tag)
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
        try {
            node.performScrollTo()
        } catch (_: Throwable) {
            // Not inside a scrollable container.
        }
        node.performClick()
        rule.waitForIdle()
    }

    /** The scrolling key area around [tag]: its nearest vertical-scroll ancestor that can scroll. */
    private fun keyArea(tag: String): SemanticsNode? =
        generateSequence(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().parent) { it.parent }.firstOrNull {
            (
                it.config
                    .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    ?.maxValue
                    ?.invoke() ?: 0f
            ) > 0f
        }

    /** Drags the key area (a real touch drag, not a semantics scroll) until [tag] is fully inside it. */
    private fun dragKeyAreaTo(tag: String) {
        repeat(MAX_DRAGS) {
            val area = keyArea(tag) ?: return
            val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val key = Rect(node.positionInRoot, node.size.toSize())
            val view = area.boundsInRoot
            val dy =
                when {
                    key.top < view.top -> view.top - key.top
                    key.bottom > view.bottom -> view.bottom - key.bottom
                    else -> return
                }
            rule.onNode(SemanticsMatcher("key area") { it.id == area.id }, useUnmergedTree = true).performTouchInput {
                swipe(center, center + Offset(0f, dy + viewConfiguration.touchSlop * sign(dy)), DRAG_MS)
            }
            rule.waitForIdle()
        }
    }

    /** Taps the Move key for [move] and waits for the session to take it. */
    fun tapMove(move: Move) {
        val before = host.state.value.revision
        val tag = "moveKey:${Notation.format(move)}"
        // Wide and slice keys sit behind the key bar's *more* toggle.
        if (!exists(tag) && exists("moveKey:more")) click("moveKey:more")
        awaitTag(tag)
        // The key's row scrolls sideways; a short stage's key area scrolls vertically, which
        // performScrollTo (nearest scroll parent only) does not reach, so drag it as a Learner would.
        rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        dragKeyAreaTo(tag)
        click(tag)
        await("Move ${Notation.format(move)} to commit") { host.state.value.revision != before }
        rule.waitForIdle()
    }

    /** Lets the app settle: Compose idle and no state change for a short while. */
    fun settle() {
        rule.waitForIdle()
        var last = host.state.value
        var stableSince = System.currentTimeMillis()
        val deadline = stableSince + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            rule.waitForIdle()
            val now = host.state.value
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince >= STABLE_MS) {
                return
            }
        }
    }

    /**
     * The rotation check at one surface: portrait → landscape → portrait. Fails naming the flow and
     * [surface] when the session or the visible cube/step changes, or when the layout does not follow
     * the window class.
     */
    fun surface(surface: String) {
        surfaces++
        settle()
        val problems = mutableListOf<String>()
        softly(problems) { checkLayout(surface, "portrait") }
        listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
        ).forEach { (orientation, name) ->
            val before = host.state.value
            val visible = visibleState()
            rotate(orientation)
            settle()
            val after = host.state.value
            if (after != before) {
                problems += "$flow / $surface ($name): session state changed: ${changedFields(before, after)}"
            }
            softly(problems) { assertEquals("$flow / $surface ($name): visible cube/step", visible, visibleState()) }
            softly(problems) { checkLayout(surface, name) }
        }
        val result = if (problems.isEmpty()) "pass" else "fail"
        Log.i(TAG, "surface,flow=$flow,surface=$surface,result=$result")
        problems.forEach { Log.w(TAG, "problem,$it") }
        failures += problems
    }

    /** The [SessionState] constructor properties that differ, with short before → after values. */
    private fun changedFields(
        before: SessionState,
        after: SessionState,
    ): String {
        val changed =
            SessionState::class.java.declaredFields
                .filter {
                    !it.name.endsWith("\$delegate") &&
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.filter { field ->
                    field.isAccessible = true
                    field.get(before) != field.get(after)
                }.map { it.name }
        return "fields ${changed.sorted()}; revision ${before.revision} -> ${after.revision}; " +
            "lesson ${before.lesson?.id} -> ${after.lesson?.id}; kind ${before.kind} -> ${after.kind}"
    }

    /**
     * Collects an assertion failure instead of stopping the flow, so one broken surface does not hide
     * the rest of the flow's matrix; [finish] fails the test with every collected problem.
     */
    private fun softly(
        problems: MutableList<String>,
        check: () -> Unit,
    ) {
        try {
            check()
        } catch (failure: AssertionError) {
            problems += failure.message.orEmpty()
        }
    }

    fun portrait() {
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        settle()
    }

    fun restoreOrientation() {
        try {
            val activity = activity()
            rule.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        } catch (_: Throwable) {
            // The activity is already gone.
        }
    }

    fun finish() {
        val result = if (failures.isEmpty()) "pass" else "fail"
        Log.i(TAG, "flow,flow=$flow,surfaces=$surfaces,failedSurfaceChecks=${failures.size},result=$result")
        if (failures.isNotEmpty()) fail("$flow: ${failures.size} rotation check(s) failed:\n" + failures.joinToString("\n"))
    }

    private fun rotate(orientation: Int) {
        val want =
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        val old = activity()
        val recreate = old.resources.configuration.orientation != want
        rule.runOnUiThread { old.requestedOrientation = orientation }
        await("rotation to ${if (want == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}", ROTATE_TIMEOUT_MS) {
            val current =
                try {
                    activity()
                } catch (_: Throwable) {
                    null
                }
            current != null && (!recreate || current !== old) && current.resources.configuration.orientation == want &&
                !current.isFinishing
        }
        rule.waitForIdle()
    }

    /** What the Learner sees of the cube and the step: the cube's spoken summary and state, and the step counter. */
    private fun visibleState(): List<String?> {
        val cubes = rule.onAllNodesWithTag("cube3d", useUnmergedTree = true).fetchSemanticsNodes()
        val cube =
            cubes.map {
                listOf(
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
                    it.config.getOrNull(SemanticsProperties.StateDescription),
                ).joinToString("|")
            }
        val step =
            rule
                .onAllNodesWithTag("lesson:stepNumber", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        return cube + step
    }

    /** The window class from the window's width in dp (the root composable spans the window). */
    fun windowClass(): Pair<WindowWidthClass, Float> {
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first()
        val density = activity().resources.displayMetrics.density
        val widthDp = root.size.width / density
        return WindowWidthClass.of(widthDp) to widthDp
    }

    private fun bounds(tag: String) =
        rule
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot

    private fun checkLayout(
        surface: String,
        orientation: String,
    ) {
        val (widthClass, widthDp) = windowClass()
        val density = activity().resources.displayMetrics.density
        val where = "$flow / $surface ($orientation, ${widthDp.roundToInt()}dp, $widthClass)"
        val checks = mutableListOf<String>()
        if (exists("shell")) {
            val compact = widthClass == WindowWidthClass.COMPACT
            assertEquals("$where: nav:bar", compact, exists("nav:bar"))
            assertEquals("$where: nav:rail", !compact, exists("nav:rail"))
            checks += if (compact) "nav:bar" else "nav:rail"
        }
        if (exists("lesson:stage") && exists("lesson:panel") && !exists("lesson:manualEditor")) {
            val stage = bounds("lesson:stage")
            val panel = bounds("lesson:panel")
            if (widthClass == WindowWidthClass.COMPACT) {
                assertTrue("$where: panel below stage ($panel vs $stage)", panel.top >= stage.bottom - 1f)
                checks += "lesson:stacked"
            } else {
                assertTrue("$where: panel right of stage ($panel vs $stage)", panel.left >= stage.right - 1f)
                checks += "lesson:sideBySide"
                if (widthClass == WindowWidthClass.EXPANDED) {
                    val panelDp = panel.width / density
                    assertTrue("$where: panel ${panelDp}dp <= 560dp", panelDp <= EXPANDED_PANEL_MAX_DP + 0.5f)
                    checks += "lesson:panel<=560dp"
                }
            }
        }
        if (exists("library:grid")) {
            val grid = bounds("library:grid")
            val tile =
                rule
                    .onAllNodes(tagPrefix("library:case:"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .first()
                    .boundsInRoot
            val gap = GRID_GAP.value * density
            val columns = ((grid.width + gap) / (tile.width + gap)).roundToInt()
            val want =
                when (widthClass) {
                    WindowWidthClass.COMPACT -> 2
                    WindowWidthClass.MEDIUM -> 3
                    WindowWidthClass.EXPANDED -> 4
                }
            assertEquals("$where: Library columns", want, columns)
            checks += "library:${columns}columns"
        }
        if (exists("reviewNet") && exists("editor:accept")) {
            val onePerFace = exists("editor:face:0")
            if (widthClass == WindowWidthClass.COMPACT) {
                assertTrue("$where: compact editor edits one face at a time", onePerFace)
            } else {
                // Wider classes show the full net unless the editor's width cannot give each of the net's 12
                // columns a 48dp cell (beside a rail and padding); from NET_MIN_WINDOW_DP that always fits.
                if (widthDp >= NET_MIN_WINDOW_DP) assertTrue("$where: editor shows the full net", !onePerFace)
            }
            checks += if (onePerFace) "editor:oneFace" else "editor:net"
        }
        Log.i(
            TAG,
            "layout,flow=$flow,surface=$surface,orientation=$orientation,widthDp=${widthDp.roundToInt()},class=$widthClass," +
                "checks=${checks.joinToString("+")}",
        )
    }

    // ---- Flow helpers ----

    /** Opens [lessonId] of [stageId] from the Learn tab (Stage list or another Stage's detail). */
    fun openLesson(
        stageId: String,
        lessonId: String,
    ) {
        val lesson = "learn:lesson:$lessonId"
        val stage = "learn:stage:$stageId"
        await("the Stage list or a Stage detail") { exists(lesson) || exists(stage) || exists("stageDetail") }
        if (!exists(lesson) && exists("stageDetail")) click("stageDetail:back")
        if (!exists(lesson)) click(stage)
        click(lesson)
        await("Lesson $lessonId to start") {
            host.state.value.lesson
                ?.id
                ?.value == lessonId
        }
        awaitTag("lesson:panel")
        settle()
    }

    /** The next authored Move of the open Lesson, from the Tutor's path. */
    fun nextAuthoredMove(state: SessionState = host.state.value): Move? =
        state.lesson?.let { Tutor.nextMove(it, state.pathEntry, state.virtual) }

    /**
     * Plays the open Lesson on the virtual cube with the Move keys (the authored path, or a computed
     * Solution when it has none), tapping *Next* whenever it is enabled, until the Lesson closes, a
     * milestone shows or the 2-look *Add to Learning* offer shows. [onEachMove] runs after every Move (for mid-step checks).
     */
    fun playLesson(onEachMove: (Int) -> Unit = {}) {
        var moves = 0
        var planned = ArrayDeque<Move>()
        repeat(MAX_ACTIONS) {
            settle()
            if (exists("milestone:overlay") || !exists("lesson") || exists("lesson:twoLookOffer:add")) return
            if (exists("lesson:next") && enabled("lesson:next")) {
                click("lesson:next")
                planned.clear()
                return@repeat
            }
            val state = host.state.value
            val move =
                nextAuthoredMove(state) ?: planned.removeFirstOrNull() ?: run {
                    // The solver works in the home frame: Cube Rotations back to it first, then its Solution.
                    val home = homeRotations(state.virtual)
                    planned = ArrayDeque(home + solve(state.virtual.apply(home)))
                    planned.removeFirstOrNull()
                }
            if (move == null) fail("$flow: Lesson ${state.lesson?.id} has no next Move and no enabled Next")
            tapMove(move!!)
            onEachMove(++moves)
        }
        fail("$flow: Lesson did not finish in $MAX_ACTIONS actions")
    }

    /** Taps *Done* in Physical-cube Mode (one authored Move per tap) until *Next* or *Yes, it's solved* shows. */
    fun confirmPhysicalSteps(onEachDone: (Int) -> Unit = {}) {
        var done = 0
        repeat(MAX_ACTIONS) {
            settle()
            if (exists("lesson:yesItsSolved") || (exists("lesson:next") && enabled("lesson:next"))) return
            click("lesson:done")
            onEachDone(++done)
        }
        fail("$flow: physical steps did not finish in $MAX_ACTIONS taps")
    }

    /** Up to three Cube Rotations that bring [state] back to the home frame (WHITE up, GREEN front). */
    private fun homeRotations(state: CubeState): List<Move> {
        val home = CubeState.SOLVED.orientation
        if (state.orientation == home) return emptyList()
        val rotations = MoveKeys.rotation
        val queue = ArrayDeque(rotations.map { listOf(it) })
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (state.apply(path).orientation == home) return path
            if (path.size < MAX_ROTATIONS) rotations.forEach { queue += path + it }
        }
        error("$flow: no Cube Rotation path to the home frame from ${state.orientation}")
    }

    /** A verified Solution for [state], from the app's production solver (bundled tables). */
    fun solve(state: CubeState): List<Move> =
        runBlocking {
            val context = activity().applicationContext
            val source = SolverModule.source(SolverModule.provider(context))
            val solver = (source.load() as? SolverLoad.Ready)?.solver ?: error("$flow: solver did not load")
            when (val result = solver.solve(state.cubies)) {
                is SolveResult.Solved -> {
                    val end = state.apply(result.moves).toFacelets().colors
                    check(end.chunked(FACELETS_PER_FACE).all { face -> face.all { it == face[0] } }) { "$flow: Solution does not solve" }
                    result.moves
                }

                else -> {
                    error("$flow: no Solution ($result)")
                }
            }
        }

    companion object {
        const val TAG = "KeyFlowRotation"
        private const val TIMEOUT_MS = 20_000L
        private const val ROTATE_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 100L
        private const val MAX_DRAGS = 6
        private const val DRAG_MS = 600L
        private const val STABLE_MS = 400L
        private const val MAX_ACTIONS = 400
        private const val EXPANDED_PANEL_MAX_DP = 560f
        private val GRID_GAP = 8.dp
        private const val NET_MIN_WINDOW_DP = 12 * 48f + 80f + 64f
        private const val FACELETS_PER_FACE = 9
        private const val MAX_ROTATIONS = 3
    }
}
