package dev.cubentia.app.compat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cubentia.app.MainActivity
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 12.2 AC3 (NFR-13, AD-17): every v1.0 Key Flow (EXPERIENCE.md Flows 1, 2, 3, 6, 7, 8 and 9
 * step 4; camera steps are v1.1) driven through the UI of the real [MainActivity] from a cleared
 * install, rotating portrait → landscape → portrait at each surface. At every surface the session
 * and the visible cube and step must be unchanged and the layout must follow the window class.
 *
 * Each method needs a cleared install: the host runs `pm clear com.exxerpro.cubentia` before each
 * one (`tools/run-keyflow-rotation.sh <serial>`). Preconditions are reached through the UI only:
 * Flow 2 unlocks *Faster last layer* by passing the skip-ahead check (Flow 8), and Flow 7 completes
 * Stage 1 to unlock Stage 2's practice. Skip checks and practice are solved by tapping the Move keys
 * of a Solution computed in-process by the app's production solver for the dealt state.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowRotationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var harness: KeyFlowHarness

    private fun flow(name: String): KeyFlowHarness = KeyFlowHarness(rule, name).also { harness = it }

    @Before
    fun portraitFirst() {
        KeyFlowHarness(rule, "setup").portrait()
    }

    @After
    fun restoreOrientation() {
        KeyFlowHarness(rule, "teardown").restoreOrientation()
    }

    /** Flow 1, camera-free: Welcome → Stage 1 on the virtual cube → Stage 2 in Physical-cube Mode → the Course finale. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow1FirstSolvePhysicalCube() =
        with(flow("flow1")) {
            awaitTag("welcome")
            surface("welcome")
            click("welcome:imNew")
            awaitTag("stageDetail")
            surface("stage1Detail")
            // Stage 1 on the virtual cube; rotate on the open Lesson and mid-step.
            openLesson(STAGE_1, "lesson-01-01")
            surface("lesson-01-01")
            playLesson()
            openLesson(STAGE_1, "lesson-01-02")
            playLesson { moves -> if (moves == 1) surface("lesson-01-02 midStep") }
            openLesson(STAGE_1, "lesson-01-03")
            playLesson()
            // Stage 2 in Physical-cube Mode: Confirm your cube, Done, then a reported mismatch.
            openLesson(STAGE_2, "lesson-02-01")
            click("lesson:physicalModeSwitch")
            awaitTag("confirmCube:card")
            surface("confirmCube")
            click("confirmCube:matches")
            await("frame EXPECTED") { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            surface("physicalStep")
            click("lesson:overflowMenu")
            click("lesson:menuMismatch")
            awaitTag("mismatch:ladder")
            surface("mismatchLadder")
            click("mismatch:matchesAfterAll")
            confirmPhysicalSteps()
            surface("physicalStepDone")
            click("lesson:next")
            // The rest of the Course on the virtual cube, then the finale in Physical-cube Mode.
            COURSE.drop(4).dropLast(1).forEach { (stage, lesson) ->
                openLesson(stage, lesson)
                if (host.state.value.syncState != SyncState.VIRTUAL) click("lesson:physicalModeSwitch")
                playLesson()
            }
            openLesson(STAGE_6, "lesson-06-03")
            if (host.state.value.syncState == SyncState.VIRTUAL) click("lesson:physicalModeSwitch")
            if (exists("confirmCube:matches")) click("confirmCube:matches")
            // Done for each Move, Next between the finale's steps, until *Yes, it's solved* is offered.
            var finaleSteps = 0
            confirmPhysicalSteps()
            while (!exists("lesson:yesItsSolved")) {
                check(++finaleSteps < MAX_STEPS) { "flow1: the finale never offered Yes, it's solved" }
                click("lesson:next")
                confirmPhysicalSteps()
            }
            surface("finaleYesItsSolved")
            click("lesson:yesItsSolved")
            awaitTag("milestone:overlay")
            surface("firstSolveMilestone")
            click("milestone:continue")
            awaitTag("shell")
            surface("learnAfterFirstSolve")
            finish()
        }

    /** Flow 2: *Faster last layer* unlocked by the skip-ahead check, a 2-look OLL Lesson, the Learning offer, Library *Learning*, LL practice. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow2TwoLookLastLayer() =
        with(flow("flow2")) {
            passCourseSkipCheck()
            click("learn:fasterLastLayer")
            awaitTag("learn:stage:$STAGE_8")
            surface("fasterLastLayer")
            openLesson(STAGE_8, "lesson-08-01")
            surface("twoLookLesson")
            playLesson()
            awaitTag("lesson:twoLookOffer:add")
            surface("twoLookOffer")
            click("lesson:twoLookOffer:add")
            await("the offer to save") { !exists("lesson:twoLookOffer:add") }
            playLesson()
            awaitTag("shell")
            click("nav:library")
            awaitTag("library:grid")
            click("library:status:LEARNING")
            surface("libraryLearning")
            click("nav:learn")
            if (exists("stageDetail")) click("stageDetail:back")
            click("learn:twoLookPractice")
            await("LL practice") { host.state.value.kind == SessionKind.PRACTICE }
            surface("twoLookPractice")
            playLesson()
            if (exists("milestone:overlay")) {
                surface("twoLookMilestone")
                click("milestone:continue")
            }
            finish()
        }

    /** Flow 3, v1.0: Solve → *Enter colors* → Accept → playback followed on my cube → the learn offer. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow3JustSolveIt() =
        with(flow("flow3")) {
            awaitTag("welcome")
            click("welcome:imNew")
            awaitTag("shell")
            click("nav:solve")
            awaitTag("solve:enterColors")
            surface("solveHome")
            enterColorsAndAccept(this)
            click("playback:followOnMyCube")
            awaitTag("playback:done")
            surface("playbackFollowing")
            var steps = 0
            while (!exists("solve:learnOffer")) {
                click("playback:done")
                if (++steps == 1) surface("playbackStep")
                check(steps < MAX_STEPS) { "flow3: playback never ended" }
            }
            surface("learnOffer")
            click("solvePlayback:close")
            awaitTag("shell")
            finish()
        }

    /** Flow 6: five-minute evaluation — gestures, View Rotation, Library OLL detail scrub, Enter colors and solve. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow6FiveMinuteEvaluation() =
        with(flow("flow6")) {
            awaitTag("welcome")
            click("welcome:imNew")
            awaitTag("shell")
            click("nav:solve")
            awaitTag("cube3d")
            // A face-turn gesture: a swipe across the cube's middle row.
            val before = host.state.value.revision
            rule.onNodeWithTag("cube3d", useUnmergedTree = true).performTouchInput {
                swipe(center.copy(x = center.x - width / 8f), center.copy(x = center.x + width / 8f), 300)
            }
            settle()
            surface("freeCubeAfterGesture")
            // View Rotation: a drag from the stage's corner, off the cube, then the view menu.
            rule.onNodeWithTag("cube3d", useUnmergedTree = true).performTouchInput {
                swipe(topLeft.copy(x = width * 0.05f, y = height * 0.05f), topLeft.copy(x = width * 0.6f, y = height * 0.05f), 400)
            }
            surface("freeCubeAfterViewRotation")
            assertTrue("flow6: free cube is not a session (${host.state.value.kind}, revision $before)", host.state.value.kind == null)
            click("nav:library")
            awaitTag("library:grid")
            surface("library")
            click(firstTagStartingWith("library:case:case-oll"))
            awaitTag("algorithmDetail:title")
            surface("algorithmDetail")
            if (exists("playback:scrub")) {
                rule.onNodeWithTag("playback:scrub", useUnmergedTree = true).performTouchInput {
                    swipe(centerLeft, centerRight, 300)
                    swipe(centerRight, center, 300)
                }
                surface("algorithmDetailScrubbed")
            }
            click("algorithmDetail:back")
            click("nav:solve")
            enterColorsAndAccept(this)
            surface("playbackVirtual")
            click("solvePlayback:close")
            finish()
        }

    /** Flow 7: Stage 2 practice to mastery, then the trend row on You. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow7PracticeToMastery() =
        with(flow("flow7")) {
            awaitTag("welcome")
            click("welcome:imNew")
            awaitTag("stageDetail")
            COURSE.take(3).forEach { (stage, lesson) ->
                openLesson(stage, lesson)
                playLesson()
            }
            if (exists("stageDetail")) click("stageDetail:back")
            click("learn:stage:$STAGE_2")
            awaitTag("stageDetail:practice")
            surface("stage2Detail")
            var attempts = 0
            while (!exists("milestone:overlay") && attempts < MAX_PRACTICE) {
                attempts++
                if (!exists("lesson")) click("stageDetail:practice")
                await("Stage 2 practice") { host.state.value.kind == SessionKind.PRACTICE }
                awaitTag("lesson:panel")
                if (attempts == 1) {
                    surface("practiceDealt")
                    if (exists("lesson:hint") && enabled("lesson:hint")) click("lesson:hint")
                    surface("practiceHint")
                }
                playLesson()
            }
            awaitTag("milestone:overlay")
            surface("stageMasteredMilestone")
            // A failed check above can leave a re-dealt practice instead of the milestone; leave either way.
            if (exists("milestone:continue")) click("milestone:continue") else click("lesson:close")
            awaitTag("shell")
            click("nav:you")
            awaitTag("you:screen")
            surface("youTrend")
            finish()
        }

    /** Flow 8: *I can already solve it* → the skip-ahead check solved → Stages Skipped, *Faster last layer* unlocked. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow8SkipAhead() =
        with(flow("flow8")) {
            passCourseSkipCheck()
            surface("learnSkipped")
            assertTrue("flow8: Faster last layer unlocked", exists("learn:fasterLastLayer"))
            finish()
        }

    /** Flow 9 step 4: Settings → *Reset progress* ends the live Lesson and clears the Continue card. */
    @Test(timeout = FLOW_TIMEOUT_MS)
    fun flow9ResetProgress() =
        with(flow("flow9")) {
            awaitTag("welcome")
            click("welcome:imNew")
            openLesson(STAGE_1, "lesson-01-01")
            playLesson()
            openLesson(STAGE_1, "lesson-01-02")
            tapMove(FaceTurn(Face.R, Turn.CLOCKWISE))
            click("lesson:close")
            awaitTag("shell")
            if (exists("stageDetail")) click("stageDetail:back")
            awaitTag("learn:continue")
            surface("learnContinue")
            click("nav:you")
            click("you:settings")
            awaitTag("settings:screen")
            surface("settings")
            click("settings:resetProgress")
            awaitTag("settings:resetProgress:confirm")
            surface("resetProgressSheet")
            click("settings:resetProgress:confirm")
            await("Reset progress to finish") { !exists("settings:resetProgress:confirm") }
            assertEquals("flow9: session ended", null, host.state.value.kind)
            surface("settingsAfterReset")
            click("settings:close")
            awaitTag("welcome")
            assertEquals("flow9: session ended", null, host.state.value.kind)
            surface("welcomeAfterReset")
            click("welcome:imNew")
            awaitTag("shell")
            if (exists("stageDetail")) click("stageDetail:back")
            assertTrue("flow9: Continue card cleared", !exists("learn:continue"))
            finish()
        }

    /** Welcome → *I can already solve it* → solve the dealt Scramble with the Move keys → Learn. */
    private fun KeyFlowHarness.passCourseSkipCheck() {
        awaitTag("welcome")
        click("welcome:canSolve")
        await("skip-ahead check") { host.state.value.kind == SessionKind.PRACTICE }
        awaitTag("lesson:panel")
        surface("skipCheck")
        playLesson()
        awaitTag("shell")
    }

    /** Solve → *Enter colors* → paint the stickers of an R U scramble → Accept → Solution playback. */
    private fun enterColorsAndAccept(harness: KeyFlowHarness) =
        with(harness) {
            click("solve:enterColors")
            awaitTag("editor:accept")
            surface("editor")
            val target = CubeState.SOLVED.apply(listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.CLOCKWISE))).toFacelets()
            val start = CubeState.SOLVED.toFacelets()
            target.colors.indices.filter { target.colors[it] != start.colors[it] }.forEach { index ->
                if (exists("editor:face:0") && !exists("sticker:$index")) click("editor:face:${index / 9}")
                click("sticker:$index")
                click("swatch:${target.colors[index].name}")
            }
            surface("editorEntered")
            click("editor:accept")
            await("Solution playback", SOLVE_TIMEOUT_MS) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
            awaitTag("solvePlayback:screen")
        }

    private companion object {
        const val FLOW_TIMEOUT_MS = 900_000L
        const val SOLVE_TIMEOUT_MS = 60_000L
        const val MAX_STEPS = 60
        const val MAX_PRACTICE = 6
        const val STAGE_1 = "stage-01-fundamentals"
        const val STAGE_2 = "stage-02-first-cross"
        const val STAGE_6 = "stage-06-last-layer-completion"
        const val STAGE_8 = "stage-08-two-look-oll"

        /** The Course's Lessons in order (shipped content). */
        val COURSE: List<Pair<String, String>> =
            listOf(
                STAGE_1 to "lesson-01-01",
                STAGE_1 to "lesson-01-02",
                STAGE_1 to "lesson-01-03",
                STAGE_2 to "lesson-02-01",
                STAGE_2 to "lesson-02-02",
                STAGE_2 to "lesson-02-03",
                STAGE_2 to "lesson-02-04",
                STAGE_2 to "lesson-02-05",
                "stage-03-first-layer-corners" to "lesson-03-01",
                "stage-03-first-layer-corners" to "lesson-03-02",
                "stage-03-first-layer-corners" to "lesson-03-03",
                "stage-03-first-layer-corners" to "lesson-03-04",
                "stage-04-middle-layer" to "lesson-04-01",
                "stage-04-middle-layer" to "lesson-04-02",
                "stage-04-middle-layer" to "lesson-04-03",
                "stage-04-middle-layer" to "lesson-04-04",
                "stage-05-last-layer-orientation" to "lesson-05-01",
                "stage-05-last-layer-orientation" to "lesson-05-02",
                "stage-05-last-layer-orientation" to "lesson-05-03",
                "stage-05-last-layer-orientation" to "lesson-05-04",
                STAGE_6 to "lesson-06-01",
                STAGE_6 to "lesson-06-02",
                STAGE_6 to "lesson-06-03",
            )
    }
}
