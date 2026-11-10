package dev.cubentia.app.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.freecube.MoveKeys
import dev.cubentia.app.ui.lesson.FirstSolveMilestone
import dev.cubentia.app.ui.lesson.LessonScreen
import dev.cubentia.app.ui.shell.AppShell
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun auditScreens(
    rule: ComposeContentTestRule,
    largeType: Boolean,
    expanded: Boolean = false,
) {
    val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
    val ready = CatalogState.Ready(loaded.bundle)
    val host = SessionHost(TickingClock())
    // Restore up front: the shell's own restore is cancelled when it leaves composition, which would
    // leave the Lesson waiting on awaitRestored() forever.
    runBlocking { host.restore(loaded.bundle) }
    val cube = FreeCubeViewModel()
    // The Solve tab's Enter colors flow: the first solve times out (the editor's retry state), later ones play back.
    var solveResult: SolveResult = SolveResult.TimedOut
    val solveFlow =
        SolveFlowViewModel(
            object : SolverSource {
                override val isReady = true

                override suspend fun load(): SolverLoad = SolverLoad.Ready(CubeSolver { solveResult })
            },
        )
    val catalog = LessonCatalog.of(loaded)
    val lessonEntry = ready.lessons.first()
    var surface by mutableStateOf("shell")
    val content: @Composable () -> Unit = {
        CubentiaTheme {
            when (surface) {
                "editor" -> {
                    dev.cubentia.app.ui.editor
                        .ManualCubeEditor(strings = ready.strings, onAccept = {})
                }

                "lesson" -> {
                    LessonScreen(host, lessonEntry, ready.strings, TeachingMode.entries, {})
                }

                "masteryMilestone" -> {
                    val next = ready.bundle.stages[1]
                    FirstSolveMilestone(
                        headline =
                            ready.strings.resolve(
                                dev.cubentia.domain.text.Text(
                                    "msg.milestone.stage-mastered",
                                    mapOf("stage" to ready.strings.resolve(lessonEntry.stage.title)),
                                ),
                            ),
                        onPracticeNewScramble = {},
                        onContinue = {},
                        reduceMotion = true,
                        nextStageLabel = ready.strings.resolve(next.title),
                        onNextStage = {},
                    )
                }

                "milestone" -> {
                    FirstSolveMilestone(
                        ready.strings.resolve(
                            dev.cubentia.domain.text
                                .Text("msg.milestone.first-solve"),
                        ),
                        {
                        },
                        {},
                        reduceMotion = true,
                    )
                }

                else -> {
                    AppShell(host, catalog, cube, false, testFlags(false), showWelcome = true, solveFlow = solveFlow)
                }
            }
        }
    }
    rule.setContent { if (largeType) fontScale200(content) else content() }

    fun check(name: String) {
        rule.waitForIdle()
        if (largeType) {
            rule.assertNoTextOverflow(name)
            rule.assertClickablesAtLeast48dp(name)
        } else {
            rule.assertClickablesLabelled(name)
        }
    }
    check("Welcome")
    rule.onNodeWithTag("welcome:imNew").performScrollTo().performClick()
    check("Stage detail")
    rule.onNodeWithTag("stageDetail:back").performClick()
    check("Learn")
    rule.onNodeWithTag("nav:library").performClick()
    check("Library")
    rule
        .onAllNodes(
            SemanticsMatcher("case tile") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library:case:") == true
            },
        ).fetchSemanticsNodes()
        .first()
        .let {
            rule.onNodeWithTag(it.config[SemanticsProperties.TestTag]).performClick()
        }
    check("Algorithm detail")
    rule.onNodeWithTag("algorithmDetail:back").performClick()
    rule.onNodeWithTag("nav:solve").performClick()
    check("Solve")
    if (largeType) {
        rule.onNodeWithTag("freeCube:stage").assertHeightIsAtLeast(280.dp)
        rule.auditStageExtras("Solve", ::check)
    } else {
        rule.assertButtonRoutes()
    }
    rule.onNodeWithTag("solve:enterColors").performClick()
    check("Solve Enter colors")
    rule.onNodeWithTag("editor:accept").performScrollTo().performClick()
    rule.waitUntil(10_000) { rule.onAllNodesWithTag("solve:error").fetchSemanticsNodes().isNotEmpty() }
    check("Solve Enter colors retry")
    val move = FaceTurn(Face.R, Turn.CLOCKWISE)
    solveResult = SolveResult.Solved(listOf(move.inverse()))
    rule.runOnIdle {
        solveFlow.accept(CubeState.SOLVED.apply(move)) { state, moves ->
            host.dispatch { SessionEvent.SolvePlaybackStarted(state, moves, it) }
        }
    }
    rule.waitUntil(10_000) { rule.onAllNodesWithTag("solvePlayback:screen").fetchSemanticsNodes().isNotEmpty() }
    check("Solve playback")
    rule.onNodeWithTag("playback:end").performClick()
    rule.waitUntil(10_000) { rule.onAllNodesWithTag("solve:learnOffer").fetchSemanticsNodes().isNotEmpty() }
    check("Solve playback end")
    rule.onNodeWithTag("solvePlayback:close").performClick()
    rule.waitUntil(10_000) { rule.onAllNodesWithTag("nav:you").fetchSemanticsNodes().isNotEmpty() }
    rule.onNodeWithTag("nav:you").performClick()
    check("You")
    rule.onNodeWithTag("you:settings").performClick()
    check("Settings")
    rule.onNodeWithTag("settings:resetProgress").performScrollTo().performClick()
    check("Reset dialog")
    rule.onNodeWithTag("settings:resetProgress:cancel").performClick()
    rule.runOnIdle { surface = "editor" }
    check("Manual editor")
    rule.runOnIdle { surface = "lesson" }
    // The session starts asynchronously on the host's loop; waitForIdle pumps the main looper the
    // Lesson's start effect resumes on, and the stage appears once the session is live.
    val deadline = System.currentTimeMillis() + 10_000
    while (rule.onAllNodesWithTag("lesson:stage").fetchSemanticsNodes().isEmpty()) {
        assertTrue(
            "Lesson never started: kind=${host.state.value.kind}, lesson=${host.state.value.lesson?.id}",
            System.currentTimeMillis() < deadline,
        )
        Thread.sleep(20)
        rule.waitForIdle()
    }
    check("Lesson")
    rule.onNodeWithTag("lesson:stage").assertHeightIsAtLeast(280.dp)
    if (expanded) {
        val bounds = rule.onNodeWithTag("lesson:panel").getUnclippedBoundsInRoot()
        assertTrue("Expanded panel width ${bounds.right - bounds.left}", bounds.right - bounds.left <= 560.dp)
    }
    val panel = rule.onNodeWithTag("lesson:panel").assert(hasScrollAction())
    panel.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
    rule.waitForIdle()
    rule
        .onNodeWithTag("lesson:panel")
        .onChildren()
        .onLast()
        .assertIsDisplayed()
    check("Lesson scrolled to end")
    if (largeType) rule.auditStageExtras("Lesson", ::check) else rule.assertButtonRoutes()
    if (rule.onAllNodesWithTag("lesson:physicalModeSwitch").fetchSemanticsNodes().isNotEmpty()) {
        rule.onNodeWithTag("lesson:physicalModeSwitch").performClick()
        rule.waitForIdle()
        check("Lesson physical")
        rule.onNodeWithTag("lesson:stage").assertHeightIsAtLeast(280.dp)
    }
    rule.runOnIdle { surface = "milestone" }
    check("First solve milestone")
    rule.runOnIdle { surface = "masteryMilestone" }
    rule.onNodeWithTag("milestone:nextStage").assertExists()
    check("Stage mastery milestone")
}

/** Opens the extra Move keys and the View Rotation menu, audits each while shown, then closes them. */
private fun ComposeContentTestRule.auditStageExtras(
    screen: String,
    check: (String) -> Unit,
) {
    onNodeWithTag("moveKey:more").performScrollTo().performClick()
    check("$screen extra Move keys")
    // With every key row open the cube keeps its own minimum (the Lesson's CUBE_MIN, 96dp).
    onNodeWithTag("cube3d").assertHeightIsAtLeast(96.dp)
    onNodeWithTag("moveKey:more").performScrollTo().performClick()
    onNodeWithTag("stage:viewMenu").performClick()
    check("$screen View Rotation menu")
    onNodeWithTag("viewRotate:left").performClick()
    waitForIdle()
    assertTrue("$screen View Rotation menu closed", onAllNodesWithTag("viewRotate:left").fetchSemanticsNodes().isEmpty())
}

private fun ComposeContentTestRule.assertButtonRoutes() {
    assertEquals("face turns on the Move key bar", 18, MoveKeys.face.size)
    MoveKeys.face.forEach { onNodeWithTag("moveKey:${Notation.format(it)}").assertHasClickAction() }
    MoveKeys.rotation.forEach { onNodeWithTag("moveKey:${Notation.format(it)}").assertHasClickAction() }
    onNodeWithTag("moveKey:more").assertHasClickAction().performScrollTo().performClick()
    (MoveKeys.wide + MoveKeys.slice).forEach { onNodeWithTag("moveKey:${Notation.format(it)}").assertHasClickAction() }
    onNodeWithTag("moveKey:more").performClick()
    onNodeWithTag("stage:resetView").assertHasClickAction()
    onNodeWithTag("stage:viewMenu").performClick()
    listOf("left", "right", "up", "down").forEach { onNodeWithTag("viewRotate:$it").assertHasClickAction() }
    onNodeWithTag("viewRotate:left").performClick()
    val actions = onNodeWithTag("cube3d").fetchSemanticsNode().config[SemanticsActions.CustomActions]
    assertTrue("Read faces route", actions.any { it.label == "Read faces" })
}
