package dev.rubikxplorer.app.ui.lesson

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderedArrow
import dev.rubikxplorer.app.render.RenderedArrowKey
import dev.rubikxplorer.app.render.RenderedStickersKey
import dev.rubikxplorer.app.render.StickerDimmedKey
import dev.rubikxplorer.app.render.StickerHighlightedKey
import dev.rubikxplorer.app.render.StickerPulsingKey
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.oneLongStepLesson
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.testFlags
import dev.rubikxplorer.app.session.twoStepLesson
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.shell.AppShell
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.port.FlagLevel
import dev.rubikxplorer.application.port.SessionSnapshotRepository
import dev.rubikxplorer.application.port.SnapshotLoad
import dev.rubikxplorer.application.port.SnapshotSave
import dev.rubikxplorer.application.session.GuidedRecovery
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.application.session.SessionState
import dev.rubikxplorer.application.session.reduce
import dev.rubikxplorer.application.usecase.PlanRecovery
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.content.AdaptiveThresholds
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.Vector3
import dev.rubikxplorer.domain.input.ViewOrbit
import dev.rubikxplorer.domain.input.ViewPose
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.playback.PlaybackSpeed
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.Certainty
import dev.rubikxplorer.domain.tutor.HintLadder
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.RecoveryPlan
import dev.rubikxplorer.domain.tutor.RecoveryPlanner
import dev.rubikxplorer.domain.tutor.TeachingMode
import dev.rubikxplorer.domain.tutor.Tutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant

/** AC 2–4: Show me follows the Demonstration, Guide me reveals on request, and the Objective-met state. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class LessonScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun restoredLessonIsKeptBeforeStartCheck() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(bundle)
        val entry = ready.lessons.first()
        val original =
            reduce(
                reduce(
                    SessionState.IDLE,
                    SessionEvent.LessonStarted(entry.lesson, entry.tutorConfig, Instant.fromEpochMilliseconds(0)),
                ).state,
                SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), Instant.fromEpochMilliseconds(1)),
            ).state
        val snapshot =
            checkNotNull(
                SessionSnapshot.of(original, "old", bundle.contentRevision),
            )
        val repository =
            object : SessionSnapshotRepository {
                override suspend fun latest() = SnapshotLoad.Found(snapshot)

                override suspend fun save(snapshot: SessionSnapshot) = SnapshotSave.Saved
            }
        val writer = SerialWriter()
        val restoringHost =
            SessionHost(
                TickingClock(),
                Dispatchers.Default.limitedParallelism(1),
                repository,
                writer,
            )
        try {
            rule.setContent {
                RubikXplorerTheme {
                    LessonScreen(restoringHost, entry, ready.strings, TeachingMode.entries, {})
                }
            }
            rule.waitForIdle()
            assertFalse(restoringHost.state.value.isActive)
            runBlocking { restoringHost.restore(bundle) }
            rule.waitForIdle()
            assertEquals(original.history, restoringHost.state.value.history)
            assertEquals(1L, restoringHost.state.value.revision)
        } finally {
            runBlocking { writer.close() }
        }
    }

    @Test fun contentChangedCheckpointShowsNoteAndKeepsStoredCube() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(bundle)
        val entry = ready.lessons.first()
        val start = reduce(SessionState.IDLE, SessionEvent.LessonStarted(entry.lesson, entry.tutorConfig, Instant.fromEpochMilliseconds(0)))
        val storedCube = start.state.virtual.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        val checkpoint =
            dev.rubikxplorer.application.session.Checkpoint
                .of(
                    start.effects.single() as dev.rubikxplorer.application.session.Effect.PersistCheckpoint,
                    "old-content",
                ).copy(
                    cube =
                        dev.rubikxplorer.domain.content.FaceletString
                            .encode(storedCube.toFacelets()),
                    history = "R",
                )
        val writer = SerialWriter()
        val repository =
            object : dev.rubikxplorer.application.port.CheckpointRepository {
                override suspend fun find(kind: dev.rubikxplorer.application.session.SessionKind) =
                    dev.rubikxplorer.application.port.CheckpointLoad
                        .Found(checkpoint)

                override suspend fun save(checkpoint: dev.rubikxplorer.application.session.Checkpoint) =
                    dev.rubikxplorer.application.port.CheckpointSave.Saved
            }
        val restoringHost =
            SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), writer = writer, checkpoints = repository)
        try {
            runBlocking {
                restoringHost.restore(bundle)
                restoringHost.awaitRestored()
            }
            rule.setContent { RubikXplorerTheme { LessonScreen(restoringHost, entry, ready.strings, TeachingMode.entries, {}) } }
            rule.waitForIdle()
            rule
                .onNodeWithTag(
                    "lesson:restoreNote",
                ).assertTextEquals("This lesson was updated. We've restarted it with your cube as it was.")
            assertEquals(storedCube, restoringHost.state.value.virtual)
            assertTrue(
                restoringHost.state.value.history.done
                    .isEmpty(),
            )
        } finally {
            runBlocking { writer.close() }
        }
    }

    private val host = SessionHost(TickingClock())
    private lateinit var accessibilityHost: android.view.View

    /** The shipped four-step `lesson-02-05` (F2 R2 B2 L2), moved into the test Stage with no prerequisite. */
    private val crossLesson =
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded)
            .bundle.stages
            .flatMap { it.lessons }
            .single { it.id.value == "lesson-02-05" }
            .copy(stageId = StageId("stage-01-basics"), prerequisites = emptyList())

    /** The shipped `lesson-02-04` (U' R' U, then F'), whose first step has no target of its own. */
    private val flippedEdgeLesson =
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded)
            .bundle.stages
            .flatMap { it.lessons }
            .single { it.id.value == "lesson-02-04" }
            .copy(stageId = StageId("stage-01-basics"), prerequisites = emptyList())

    /** The shipped `lesson-03-01` (R' D' R), whose entry has the cross solved, moved into the test Stage with no prerequisite. */
    private val cornerLesson =
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded)
            .bundle.stages
            .flatMap { it.lessons }
            .single { it.id.value == "lesson-03-01" }
            .copy(stageId = StageId("stage-01-basics"), prerequisites = emptyList())
    private val catalog =
        LessonCatalog.of(
            ContentLoadResult.Loaded(
                testBundle(whiteCrossLesson(), twoStepLesson(), oneLongStepLesson(), crossLesson, flippedEdgeLesson, cornerLesson),
            ),
        )
    private var closed = 0

    /** The Lesson screen alone for [lessonId]. */
    private fun open(
        lessonId: String,
        modes: List<TeachingMode> = TeachingMode.entries,
        planRecovery: PlanRecovery = PlanRecovery(),
        adaptiveAssistance: Boolean = false,
        adaptiveLevel: FlagLevel = if (adaptiveAssistance) FlagLevel.GA else FlagLevel.OFF,
        thresholds: AdaptiveThresholds? = null,
    ) {
        runBlocking { catalog.load() }
        val ready = catalog.state.value as CatalogState.Ready
        val shipped = ready.lessons.single { it.lesson.id.value == lessonId }
        val entry = thresholds?.let { shipped.copy(tutorConfig = shipped.tutorConfig.copy(adaptive = it)) } ?: shipped
        rule.setContent {
            accessibilityHost = androidx.compose.ui.platform.LocalView.current
            RubikXplorerTheme {
                LessonScreen(
                    host = host,
                    entry = entry,
                    strings = ready.strings,
                    modes = modes,
                    onClose = { closed++ },
                    planRecovery = planRecovery,
                    adaptiveAssistance = adaptiveLevel,
                )
            }
        }
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == lessonId
        }
        rule.waitForIdle()
    }

    @Test
    fun `lesson twist commits through the session and drains to the virtual state like the Move key`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        val before = host.state.value
        node("cube3d").performTouchInput {
            down(0, center - Offset(50f, 0f))
            down(1, center + Offset(50f, 0f))
            moveTo(0, center - Offset(35f, 35f), delayMillis = 0)
            moveTo(1, center + Offset(35f, 35f))
            up(0)
            up(1)
        }
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        val z = CubeRotation(Axis.Z, Turn.CLOCKWISE)
        assertEquals(before.virtual.apply(z), host.state.value.virtual)
        assertEquals(before.history.done + z, host.state.value.history.done)
        assertNetMatchesSession("gesture drains to the committed virtual state")
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) {
            host.state.value.history.done
                .isEmpty()
        }
        node("moveKey:z").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertEquals(before.virtual.apply(z), host.state.value.virtual)
        assertNetMatchesSession("Move key and gesture agree")
    }

    @Test
    fun `lesson layer-turn drag commits through the session like the Move key`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        val before = host.state.value
        val bounds = node("cube3d").fetchSemanticsNode().boundsInRoot
        val pose = ViewPose(bounds.width.toInt(), bounds.height.toInt(), ViewOrbit.DEFAULT.eye())
        // Across the top of the front face, right to left: a U turn.
        val from = Vector3(0.0, 1.0, 1.5)

        fun offset(point: Vector3): Offset = pose.project(point).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        node("cube3d").performTouchInput {
            down(offset(from))
            moveTo(offset(from + Vector3(-1.0, 0.0, 0.0)))
            up()
        }
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        val u = FaceTurn(Face.U, Turn.CLOCKWISE)
        assertEquals(before.virtual.apply(u), host.state.value.virtual)
        assertEquals(before.history.done + u, host.state.value.history.done)
        assertNetMatchesSession("drag drains to the committed virtual state")
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) {
            host.state.value.history.done
                .isEmpty()
        }
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertEquals(before.virtual.apply(u), host.state.value.virtual)
        assertNetMatchesSession("Move key and drag agree")
    }

    @Test
    fun `lesson view controls rotate and reset the camera without touching the session`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        val before = host.state.value
        val bounds = node("cube3d").fetchSemanticsNode().boundsInRoot
        val from = Vector3(0.0, 1.0, 1.5)
        val u = FaceTurn(Face.U, Turn.CLOCKWISE)

        fun dragU(orbit: ViewOrbit) {
            val pose = ViewPose(bounds.width.toInt(), bounds.height.toInt(), orbit.eye())

            fun offset(point: Vector3): Offset = pose.project(point).let { Offset(it.x.toFloat(), it.y.toFloat()) }
            node("cube3d").performTouchInput {
                down(offset(from))
                moveTo(offset(from + Vector3(-1.0, 0.0, 0.0)))
                up()
            }
        }
        node("stage:resetView").assertIsEnabled()
        node("stage:viewMenu").performClick()
        node("viewRotate:right").performClick()
        rule.waitForIdle()
        assertEquals("rotating the view changes no Cube State", before.virtual, host.state.value.virtual)
        assertEquals("rotating the view records no Move", before.history, host.state.value.history)
        // The drag lands where the rotated camera projects the top-front edge: the Lesson cube uses that orbit.
        dragU(ViewOrbit.DEFAULT.rotated(-ViewOrbit.STEP_DEGREES, 0.0))
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertEquals(before.virtual.apply(u), host.state.value.virtual)
        node("stage:resetView").performClick()
        rule.waitForIdle()
        assertEquals("resetting the view records no Move", listOf(u), host.state.value.history.done)
        dragU(ViewOrbit.DEFAULT)
        rule.waitUntil(5_000) { host.state.value.history.done.size == 2 }
        assertEquals(before.virtual.apply(u).apply(u), host.state.value.virtual)
        assertNetMatchesSession("view controls leave the cube in step with the session")
    }

    private fun node(tag: String) = rule.onNodeWithTag(tag)

    private fun stickersWith(key: SemanticsPropertyKey<Boolean>): Set<String> =
        node("cube3d")
            .fetchSemanticsNode()
            .config[RenderedStickersKey]
            .mapIndexedNotNull { index, draw ->
                val flag =
                    when (key) {
                        StickerDimmedKey -> draw.dimmed
                        StickerHighlightedKey -> draw.highlighted
                        StickerPulsingKey -> draw.pulsing
                        else -> error("Unknown sticker flag")
                    }
                if (flag) "sticker:$index" else null
            }.toSet()

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @After
    fun resetAnimatorScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun drawnColors(): List<Color> = node("cube3d").fetchSemanticsNode().config[RenderedStickersKey].map { Color(it.fill) }

    private fun assertNetMatchesSession(step: String) {
        rule.waitForIdle()
        val expected =
            host.state.value.virtual
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        assertEquals(step, expected, drawnColors())
    }

    private fun anyPulsing(): Boolean = stickersWith(StickerPulsingKey).isNotEmpty()

    private fun assertStepTexts(
        n: Int,
        id: String,
    ) {
        node("lesson:goal").assertTextContains("Goal $n of $id")
        node("lesson:keep").assertTextContains("Keep $n of $id")
        node("lesson:watch").assertTextContains("Watch $n of $id")
    }

    @Test
    fun `show me follows the playing Demonstration, Notice after each step's Moves`() {
        val id = twoStepLesson().id.value
        open(id)
        node("mode:SHOW_ME").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.teachingMode == TeachingMode.SHOW_ME }
        rule.waitForIdle()
        node("mode:SHOW_ME").assertIsSelected()
        node("lesson:hint").performScrollTo().assertIsNotEnabled()
        node("demonstrationPlayer").assertExists()
        assertStepTexts(1, id)
        node("lesson:notice").assertDoesNotExist()
        node("moveChip:U").assertExists()
        node("lesson:showMove").assertDoesNotExist()
        // Show me already reveals, so there is nothing for Hint to ask.
        node("lesson:hint").assertIsNotEnabled()

        rule.mainClock.autoAdvance = false
        node("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        // Play emits step 1's only Move at once.
        assertStepTexts(1, id)
        node("lesson:notice").assertTextContains("Notice 1 of $id")

        rule.mainClock.advanceTimeBy(PlaybackSpeed.NORMAL.stepMillis + 100)
        assertStepTexts(2, id)
        node("moveChip:F'").assertExists()
        node("lesson:notice").assertTextContains("Notice 2 of $id")
        node("playback:counter").assertTextContains("2 / 2")
        // Playback never touches the session.
        assertEquals(host.state.value.entry, host.state.value.virtual)
    }

    @Test
    fun `guide me states the step first and shows the Move only once the session reveals it`() {
        val id = whiteCrossLesson().id.value
        open(id)
        node("mode:GUIDE_ME").assertIsSelected()
        assertStepTexts(1, id)
        node("lesson:notice").assertDoesNotExist()
        node("moveChip:F'").assertDoesNotExist()
        node("lesson:hint").assertIsEnabled()
        node("lesson:next").assertIsNotEnabled()
        assertFalse(
            host.state.value.decision!!
                .reveal,
        )

        node("lesson:showMove").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .reveal
        }
        rule.waitForIdle()
        node("moveChip:F'").assertExists()
        node("lesson:showMove").assertDoesNotExist()
        // Show the Move is not a Hint: the ladder stays at rung 0 and can still be climbed.
        node("lesson:hint").assertIsEnabled().assertTextContains("Hint (0/3)")
        assertEquals(0, host.state.value.hintsUsed)
        assertEquals(host.state.value.entry, host.state.value.virtual)
    }

    private fun tapHint(rung: HintRung) {
        node("lesson:hint").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.hintRung == rung }
        rule.waitForIdle()
    }

    @Test
    fun `each hint climbs one rung, its label and line following, and the Moves show from rung 2 in test me`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        node("lesson:hint").performScrollTo().assertTextContains("Hint (0/3)")
        node("lesson:hint:line").assertDoesNotExist()
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))

        tapHint(HintRung.CLUE)
        node("lesson:hint").assertTextContains("Hint (1/3)")
        node("lesson:hint:line").assertTextContains(
            "Find the white-green edge. Where does it belong, and which face can carry it there?",
        )
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:movesHidden").assertExists()
        assertTrue("no outline at the clue", stickersWith(StickerHighlightedKey).isEmpty())

        tapHint(HintRung.TARGET)
        node("lesson:hint").assertTextContains("Hint (2/3)")
        node("lesson:hint:line").assertTextContains("The outlined piece is the one to move. The Moves are below.")
        node("moveChip:F'").assertExists()
        node("lesson:movesHidden").assertDoesNotExist()
        // Rung 2 outlines the target in violet with nothing dimmed.
        val demonstration = (Demonstration.of(whiteCrossLesson()) as DemonstrationResult.Ready).demonstration
        assertEquals(targetStickers(demonstration, 0), stickersWith(StickerHighlightedKey))
        assertTrue(stickersWith(StickerDimmedKey).isEmpty())

        val before = host.state.value
        val next = Tutor.nextMove(whiteCrossLesson(), before.entry, before.virtual)!!
        assertEquals(RenderedArrow(Notation.format(next), 1f), node("cube3d").fetchSemanticsNode().config[RenderedArrowKey])
        tapHint(HintRung.DEMONSTRATION)
        node("lesson:hint").assertTextContains("Hint (3/3)").assertIsNotEnabled()
        node("lesson:hint:line").assertTextContains("Watch this step on the cube, then try it yourself.")
        assertEquals(3, host.state.value.hintsUsed)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)
        assertEquals(RenderedArrow(Notation.format(next), 1f), node("cube3d").fetchSemanticsNode().config[RenderedArrowKey])
    }

    @Test
    fun `rung 2 on shipped lesson-02-04's setup step outlines the piece it carries in guide me`() =
        assertSetupStepRung2(TeachingMode.GUIDE_ME)

    @Test
    fun `rung 2 on shipped lesson-02-04's setup step outlines the piece it carries in test me`() =
        assertSetupStepRung2(TeachingMode.TEST_ME)

    @Test
    fun `rung 2 on shipped lesson-02-04's setup step outlines the piece it carries in let me try`() =
        assertSetupStepRung2(TeachingMode.LET_ME_TRY)

    /** Rung 2 on `lesson-02-04` step 1, whose own target is empty, still outlines the piece it carries (white-green, checked in HintLadderTest). */
    private fun assertSetupStepRung2(mode: TeachingMode) {
        open(flippedEdgeLesson.id.value)
        switchTo(mode)
        assertEquals(emptySet<Any>(), HintLadder.target(flippedEdgeLesson, 0))
        tapHint(HintRung.CLUE)
        tapHint(HintRung.TARGET)
        rule.waitForIdle()
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val expected =
            (0 until Facelets.SIZE)
                .filter { Piece.at(facelets, it) in HintLadder.highlight(flippedEdgeLesson, 0) }
                .map { "sticker:$it" }
                .toSet()
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, stickersWith(StickerHighlightedKey))
        node("moveChip:U'").assertExists()
    }

    @Test
    fun `the rung 3 demonstration plays the step from its authored start on the net, then shows the session again`() {
        val lesson = whiteCrossLesson()
        open(lesson.id.value)
        switchTo(TeachingMode.TEST_ME)
        // Off the authored start, so the demonstration's frames differ from the session's cube.
        press("D", 1)
        tapHint(HintRung.CLUE)
        tapHint(HintRung.TARGET)
        val before = host.state.value
        assertNetMatchesSession("before the demonstration")
        // Off the path at rung TARGET: no next Move, so no arrow, but the target outline stays.
        assertEquals(null, Tutor.nextMove(lesson, before.entry, before.virtual))
        assertEquals(null, node("cube3d").fetchSemanticsNode().config.getOrNull(RenderedArrowKey))
        assertTrue(stickersWith(StickerHighlightedKey).isNotEmpty())

        rule.mainClock.autoAdvance = false
        node("lesson:hint").performScrollTo().performClick()
        var played = false
        repeat(DEMO_CHECKS) {
            if (!played) {
                rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
                played = drawnColors() == solvedColors
            }
        }
        assertTrue("the net never showed the step's Move played from its authored start", played)
        assertEquals(RenderedArrow("F'", 1f), node("cube3d").fetchSemanticsNode().config[RenderedArrowKey])
        assertEquals(before.virtual, host.state.value.virtual)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)

        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + REORIENT_STEP_MILLIS + 500)
        rule.mainClock.autoAdvance = true
        assertNetMatchesSession("net returns to the session after the demonstration")
        assertEquals(null, node("cube3d").fetchSemanticsNode().config.getOrNull(RenderedArrowKey))
        assertEquals(HintRung.DEMONSTRATION, host.state.value.hintRung)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)
    }

    @Test
    fun `on a later step the rung 3 demonstration plays only that step from its authored start`() {
        val demonstration = (Demonstration.of(crossLesson) as DemonstrationResult.Ready).demonstration
        open(crossLesson.id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        // Steps 1 and 2 by their authored Moves, each past its Notice; then D (away from the white face, U here), off the path, keeps step 3 shown.
        press("F2", 1)
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        press("R2", 2)
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 2 }
        press("D", 3)
        assertEquals(2, host.state.value.stepIndex)
        tapHint(HintRung.CLUE)
        tapHint(HintRung.TARGET)
        val before = host.state.value

        fun colors(state: dev.rubikxplorer.domain.cube.CubeState) = state.toFacelets().colors.map { Color(StickerAppearance.of(it).fill) }
        val step = demonstration.steps[2]
        val start = demonstration.start.apply(demonstration.moves.take(step.firstMove))
        val end = start.apply(step.moves)
        val later = end.apply(demonstration.steps[3].moves)
        assertNotEquals(colors(before.virtual), colors(start))

        rule.mainClock.autoAdvance = false
        node("lesson:hint").performScrollTo().performClick()
        val seen = mutableListOf<List<Color>>()
        repeat(DEMO_CHECKS * 2) {
            rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
            drawnColors().let { if (seen.lastOrNull() != it) seen += it }
        }
        rule.mainClock.autoAdvance = true
        val first = seen.indexOf(colors(start))
        val last = seen.indexOf(colors(end))
        assertTrue("the net never showed step 3's authored start", first >= 0)
        assertTrue("the net never showed step 3's authored end after its start", last > first)
        assertFalse("the net showed a later step's state", colors(later) in seen)
        assertFalse("the net showed the Lesson's entry", colors(demonstration.start) in seen)
        assertNetMatchesSession("net returns to the session after the demonstration")
        assertEquals(before.virtual, host.state.value.virtual)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)
    }

    @Test
    fun `a Move during the rung 3 demonstration stops it and the net shows the post-Move session`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        tapHint(HintRung.CLUE)
        tapHint(HintRung.TARGET)
        node("lesson:hint").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.hintRung == HintRung.DEMONSTRATION }
        node("moveKey:R").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        rule.waitForIdle()
        assertNetMatchesSession("after a Move interrupts the demonstration")
    }

    @Test
    fun `a new Objective starts the ladder at Hint (0 of 3)`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        tapHint(HintRung.CLUE)
        node("lesson:hint").assertTextContains("Hint (1/3)")
        val session = host.state.value
        runBlocking { host.dispatch { SessionEvent.LessonStarted(session.lesson!!, session.tutorConfig!!, it) } }
        rule.waitForIdle()
        node("lesson:hint").performScrollTo().assertTextContains("Hint (0/3)").assertIsEnabled()
        node("lesson:hint:line").assertDoesNotExist()
        assertEquals(0, host.state.value.hintsUsed)
    }

    @Test
    fun `show me offers the view controls, which leave the playback and the session alone`() {
        open(crossLesson.id.value)
        switchTo(TeachingMode.SHOW_ME)
        node("playback:forward").performClick()
        rule.waitForIdle()
        node("playback:counter").assertTextContains("1 / 4")
        val before = host.state.value
        val drawn = drawnColors()
        // The cube stays inside its slot, so the controls above and below it take their own taps.
        val cube = node("cube3d").fetchSemanticsNode().boundsInRoot
        assertTrue(cube.top >= node("stage:viewMenu").fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(cube.bottom <= node("playback:playPause").fetchSemanticsNode().boundsInRoot.top)
        node("stage:viewMenu").performClick()
        node("viewRotate:left").performClick()
        rule.waitForIdle()
        node("stage:viewMenu").performClick()
        node("viewRotate:left").performClick()
        rule.waitForIdle()
        node("playback:counter").assertTextContains("1 / 4")
        assertEquals("turning the view leaves the Session alone", before, host.state.value)
        assertEquals("turning the view leaves the drawn Cube State alone", drawn, drawnColors())
        node("stage:resetView").performClick()
        rule.waitForIdle()
        node("playback:counter").assertTextContains("1 / 4")
        assertEquals("resetting the view leaves the Session alone", before, host.state.value)
    }

    @Test
    fun `show me offers no hint to climb`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.SHOW_ME)
        node("lesson:hint").performScrollTo().assertIsNotEnabled().assertTextContains("Hint (0/3)")
        node("lesson:hint:line").assertDoesNotExist()
    }

    @Test
    fun `guide me through two steps, the path state enables Next, which moves on and hides the Move again`() {
        val id = twoStepLesson().id.value
        open(id)
        node("lesson:showMove").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.revealRequested }
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        rule.waitForIdle()
        node("lesson:notice").assertTextContains("Notice 1 of $id")
        node("lesson:objectiveMet").assertDoesNotExist()
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        rule.waitForIdle()
        assertStepTexts(2, id)
        node("lesson:notice").assertDoesNotExist()
        node("lesson:showMove").assertExists()
        assertEquals(0, closed)
    }

    /** The sticker tags of the current net whose piece is in the target of [lesson]'s step [index]. */
    private fun targetStickers(
        demonstration: Demonstration,
        index: Int,
    ): Set<String> {
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val target = demonstration.steps[index].focus.target
        return (0 until Facelets.SIZE).filter { Piece.at(facelets, it) in target }.map { "sticker:$it" }.toSet()
    }

    @Test
    fun `guide me emphasis follows the current step, so step 2 outlines step 2's target`() {
        val lesson = twoStepLesson()
        val demonstration = (Demonstration.of(lesson) as DemonstrationResult.Ready).demonstration
        open(lesson.id.value)
        assertEquals("step 1", targetStickers(demonstration, 0), stickersWith(StickerHighlightedKey))
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        rule.waitForIdle()
        val step2 = targetStickers(demonstration, 1)
        assertTrue("step 2 has a target", step2.isNotEmpty())
        assertEquals("step 2", step2, stickersWith(StickerHighlightedKey))
    }

    @Test
    fun `committing F prime meets the Objective with a brief pulse and no auto-advance`() {
        open(whiteCrossLesson().id.value)
        node("lesson:objectiveMet").assertDoesNotExist()
        // The target (white-green edge) is outlined but not pulsing before the Objective is met.
        assertTrue(stickersWith(StickerHighlightedKey).isNotEmpty())
        assertFalse(anyPulsing())

        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        // The clock steps by hand from here, so the ~600 ms pulse is seen while it runs.
        rule.mainClock.autoAdvance = false
        var pulsed = false
        repeat(PULSE_CHECKS) {
            if (!pulsed) {
                rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
                pulsed = anyPulsing()
            }
        }
        assertTrue("satisfied=${host.state.value.decision} but no sticker pulsed", pulsed)
        // Only the target's stickers pulse.
        assertTrue(stickersWith(StickerHighlightedKey).containsAll(stickersWith(StickerPulsingKey)))

        rule.mainClock.advanceTimeBy(2_000)
        assertFalse(anyPulsing())
        rule.mainClock.autoAdvance = true
        node("lesson").assertExists()
        node("lesson:objectiveMet").assertTextContains("Objective met")
        node("lesson:notice").assertTextContains("Notice 1 of lesson-01-01")
        node("lesson:next").assertIsEnabled()
        assertEquals(0, closed)
        node("lesson:next").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `next on a satisfied Lesson returns to Learn`() {
        rule.setContent {
            RubikXplorerTheme {
                AppShell(host = host, catalog = catalog, freeCube = FreeCubeViewModel(), showDebugToggles = false, flags = testFlags())
            }
        }
        node("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == whiteCrossLesson().id
        }
        rule.waitForIdle()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(whiteCrossSolvingMove, it) } }
        rule.waitForIdle()
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        node("learn").assertExists()
        node("lesson").assertDoesNotExist()
    }

    @Test
    fun `the guide me net follows the session through a Move, undo, redo and a confirmed reset`() {
        open(whiteCrossLesson().id.value)
        assertNetMatchesSession("start")
        node("moveKey:R").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertNetMatchesSession("R")
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { host.state.value.history.canRedo }
        assertNetMatchesSession("undo")
        node("toolbar:redo").performClick()
        rule.waitUntil(5_000) { host.state.value.history.canUndo }
        assertNetMatchesSession("redo")
        node("toolbar:reset").performClick()
        rule.waitUntil(5_000) { host.state.value.pendingReset != null }
        node("resetConfirmation:confirm").performClick()
        rule.waitUntil(5_000) { host.state.value.pendingReset == null && !host.state.value.hasUnsavedSteps }
        assertEquals(host.state.value.entry, host.state.value.virtual)
        assertNetMatchesSession("reset")
    }

    @Test
    fun `on step 2 with nothing unsaved, reset is enabled and returns to step 1`() {
        open(twoStepLesson().id.value)
        node("moveKey:U").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .advance
        }
        rule.waitForIdle()
        node("lesson:next").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        // Undo leaves nothing unsaved (only a redo), so Reset applies without asking.
        node("toolbar:undo").performClick()
        rule.waitUntil(5_000) { !host.state.value.hasUnsavedSteps }
        rule.waitForIdle()
        node("toolbar:reset").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 0 }
        rule.waitForIdle()
        node("resetConfirmation").assertDoesNotExist()
        assertStepTexts(1, twoStepLesson().id.value)
        assertNetMatchesSession("reset")
    }

    @Test
    fun `under reduce motion the Objective is met without a pulse`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        open(whiteCrossLesson().id.value)
        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.mainClock.autoAdvance = false
        repeat(PULSE_CHECKS) {
            rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
            assertFalse("pulsing under reduce motion", anyPulsing())
        }
        rule.mainClock.autoAdvance = true
        node("lesson:objectiveMet").assertTextContains("Objective met")
    }

    private fun switchTo(mode: TeachingMode) {
        node("mode:${mode.name}").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.teachingMode == mode }
        rule.waitForIdle()
    }

    private fun press(
        key: String,
        moves: Int,
    ) {
        node("moveKey:$key").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == moves }
        rule.waitForIdle()
    }

    /** The test tags of every node now shown whose tag starts with [prefix]. */
    private fun tagsStartingWith(prefix: String): List<String> =
        rule
            .onAllNodes(
                SemanticsMatcher("tag starts with $prefix") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) ==
                        true
                },
            ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    @Test
    fun `test me tells how each Move did and keeps the Moves hidden until Hint`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        node("mode:TEST_ME").assertIsSelected()
        node("lesson:feedback").assertDoesNotExist()
        node("lesson:movesHidden").assertTextContains("Hidden until you ask for a hint.")

        press("D", 1)
        node("lesson:feedback").assertTextContains("That didn't change much. Try another turn.")
        node("lesson:diagnosis").assertDoesNotExist()
        // U lifts three cross edges, but no step has kept them yet: neutral, never harmful.
        press("U", 2)
        node("lesson:feedback").assertTextContains("That didn't change much. Try another turn.")
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:showMove").assertDoesNotExist()
        assertFalse(
            host.state.value.decision!!
                .reveal,
        )

        // The first Hint is a clue; the Moves wait for the second.
        tapHint(HintRung.CLUE)
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        tapHint(HintRung.TARGET)
        assertTrue(
            host.state.value.decision!!
                .reveal,
        )
        node("moveChip:F'").assertExists()
        node("lesson:movesHidden").assertDoesNotExist()
        node("lesson:hint").assertIsEnabled().assertTextContains("Hint (2/3)")
    }

    @Test
    fun `let me try shows Goal, Keep and Watch, hides the Moves, never gives feedback and meets the Objective as Guide me does`() {
        val id = whiteCrossLesson().id.value
        open(id)
        switchTo(TeachingMode.LET_ME_TRY)
        assertStepTexts(1, id)
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:showMove").assertDoesNotExist()
        node("lesson:movesHidden").assertExists()
        node("lesson:hint").assertIsEnabled()
        // No dimming or target outline outside Guide me.
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())

        press("D", 1)
        node("lesson:feedback").assertDoesNotExist()
        press("D'", 2)
        node("lesson:feedback").assertDoesNotExist()
        press("F'", 3)
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.waitForIdle()
        node("lesson:objectiveMet").assertTextContains("Objective met")
        node("lesson:notice").assertTextContains("Notice 1 of $id")
        node("lesson:next").assertIsEnabled()
        node("lesson:feedback").assertDoesNotExist()
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    @Test
    fun `test me says the taught Move is on path`() {
        open(oneLongStepLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("U", 1)
        node("lesson:feedback").assertTextContains("That's the move — keep going.")
        assertFalse(
            host.state.value.decision!!
                .advance,
        )
    }

    @Test
    fun `test me names a turned cube in a polite live region, with the reorient prompt, Show me how and no undo`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("y", 1)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        node("lesson:diagnosis")
            .assertTextContains("Your cube is turned — hold it with yellow on the bottom, green in front.", substring = true)
        node("lesson:diagnosis").assertTextContains("Turn it back with the rotation keys.", substring = true)
        node("lesson:diagnosis").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        node("lesson:feedback").assertDoesNotExist()

        // A turned cube offers Show me how and no Undo; tapping it shows the rotation back, never turns the cube.
        rule.onNode(hasText("Undo") and hasAnyAncestor(hasTestTag("lesson:stepCard"))).assertDoesNotExist()
        node("lesson:recovery").assertDoesNotExist()
        node("lesson:reorientMoves").assertDoesNotExist()
        val turned = host.state.value.virtual
        node("lesson:showMeHow").performScrollTo().assertTextContains("Show me how").performClick()
        rule.waitForIdle()
        node("lesson:reorientMoves").assertExists()
        node("moveChip:y'").assertExists()
        node("lesson:showMeHow").assertDoesNotExist()
        assertEquals(turned, host.state.value.virtual)
    }

    @Test
    fun `Show me how animates the Cube Rotation back on the net and leaves the session unchanged`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("y", 1)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        val before = host.state.value
        val turned = before.virtual
        assertNetMatchesSession("turned cube drawn before Show me how")

        node("lesson:showMeHow").performScrollTo()
        rule.mainClock.autoAdvance = false
        node("lesson:showMeHow").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(REORIENT_STEP_MILLIS)
        // The net shows the cube turned back to the Lesson's hold, yet the session still holds it turned.
        val back =
            turned
                .apply(CubeRotation(Axis.Y, Turn.COUNTERCLOCKWISE))
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        assertEquals(back, drawnColors())
        assertEquals(turned, host.state.value.virtual)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)

        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + 500)
        rule.mainClock.autoAdvance = true
        // The demonstration ends on the session's own state; nothing was committed.
        assertNetMatchesSession("net returns to the session after Show me how")
        assertEquals(before, host.state.value)
    }

    @Test
    fun `a Move committed between Show me how rotations stops the demonstration and the net shows the post-Move session`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        press("y", 1)
        press("x", 2)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        val turned = host.state.value.virtual

        node("lesson:showMeHow").performScrollTo()
        rule.mainClock.autoAdvance = false
        node("lesson:showMeHow").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(REORIENT_STEP_MILLIS / 2)
        // Midway through the first demonstration step: its rotation is drawn; the session still holds the turned cube.
        assertNotEquals(
            turned.toFacelets().colors.map { Color(StickerAppearance.of(it).fill) },
            drawnColors(),
        )
        assertEquals(turned, host.state.value.virtual)

        // Commit F before the second demonstration rotation, then step past where it would have played.
        node("moveKey:F").performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 3 }
        val afterMove =
            host.state.value.virtual
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        // Where the second rotation would have been drawn, and again past the demonstration's hold.
        rule.mainClock.advanceTimeBy(REORIENT_STEP_MILLIS + Net2DRenderer.ITEM_MILLIS * 2)
        assertEquals("net shows the post-Move session, not stale demonstration cubies", afterMove, drawnColors())
        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + 500)
        assertEquals("net still shows the post-Move session after the hold", afterMove, drawnColors())
        rule.mainClock.autoAdvance = true
        assertNetMatchesSession("net still matches the session once the clock runs freely")
    }

    @Test
    fun `test me says an off-path Move that helps brings the Learner closer`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.TEST_ME)
        // U lifts the three placed cross edges (nothing kept yet); U' off the path brings them back.
        press("U", 1)
        press("U'", 2)
        node("lesson:feedback").assertTextContains("That works too — you're closer.")
        node("lesson:diagnosis").assertDoesNotExist()
    }

    @Test
    fun `test me names a harmful Move calmly with its recovery prompt, and keeps the Learner's cube`() {
        val id = twoStepLesson().id.value
        open(id)
        switchTo(TeachingMode.TEST_ME)
        press("U", 1)
        node("lesson:next").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.stepIndex == 1 }
        val before = host.state.value.virtual
        press("D", 2)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
        node("lesson:diagnosis").assertTextContains("That turn moved a piece you'd placed.", substring = true)
        node("lesson:diagnosis").assertTextContains("Undo, or try another turn.", substring = true)
        node("lesson:feedback").assertDoesNotExist()
        assertEquals(before.apply(FaceTurn(Face.D, Turn.CLOCKWISE)), host.state.value.virtual)
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    /** Commits `R` in *Test me* on `lesson-03-01`, which moves the solved cross, and waits for its Diagnosis. */
    private fun disturbCross() {
        press("R", 1)
        rule.waitUntil(5_000) {
            host.state.value.decision
                ?.diagnosis != null
        }
        rule.waitForIdle()
    }

    private fun left(tag: String): Float =
        node(tag)
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot.left

    @Test
    fun `a disturbed cross offers Undo, Guide me back and Try again inline and outlines the disturbed pieces`() {
        val id = cornerLesson.id.value
        open(id)
        switchTo(TeachingMode.TEST_ME)
        disturbCross()
        node("lesson:diagnosis").assertTextContains("That turn moved the cross.", substring = true)
        node("lesson:diagnosis").assertTextContains("Undo, or let me guide you back.", substring = true)

        node("lesson:recovery").assert(hasAnyAncestor(hasTestTag("lesson:stepCard")))
        node("lesson:recovery:undo").assertTextContains("Undo").assertIsEnabled()
        node("lesson:recovery:guideMeBack").assertTextContains("Guide me back").assertIsEnabled()
        node("lesson:recovery:tryAgain").assertTextContains("Try again").assertIsEnabled()
        val lefts = listOf("lesson:recovery:undo", "lesson:recovery:guideMeBack", "lesson:recovery:tryAgain").map { left(it) }
        assertEquals("left to right: $lefts", lefts.sorted(), lefts)
        assertEquals(3, lefts.toSet().size)
        rule.onAllNodes(isDialog()).assertCountEquals(0)

        // Exactly the disturbed pieces are outlined; no sticker is recolored.
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val disturbed =
            host.state.value.decision!!
                .diagnosis!!
                .disturbed
        assertTrue(disturbed.isNotEmpty())
        val expected = (0 until Facelets.SIZE).filter { Piece.at(facelets, it) in disturbed }.map { "sticker:$it" }.toSet()
        assertEquals(expected, stickersWith(StickerHighlightedKey))
        assertNetMatchesSession("the Learner's cube is drawn as it is")

        // Try again returns to the Checkpoint (the entry here) without restarting the Lesson.
        val entry = host.state.value.entry
        node("lesson:recovery:tryAgain").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.virtual == entry }
        assertNetMatchesSession("net shows the Checkpoint after Try again")
        node("lesson:recovery").assertDoesNotExist()
        assertEquals(
            id,
            host.state.value.lesson
                ?.id
                ?.value,
        )
        assertEquals(TeachingMode.TEST_ME, host.state.value.teachingMode)

        // Undo takes the harmful Move back.
        disturbCross()
        node("lesson:recovery:undo").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.history.done
                .isEmpty()
        }
        assertNetMatchesSession("net shows the entry after Undo")
        assertEquals(entry, host.state.value.virtual)
        node("lesson:recovery").assertDoesNotExist()
        assertEquals(
            id,
            host.state.value.lesson
                ?.id
                ?.value,
        )
    }

    @Test
    fun `guide me back shows the pending line, then the plan with its chip and the kept cross outlined`() {
        val release = AtomicBoolean(false)
        val held =
            PlanRecovery({ from, goal, depth, set, go ->
                while (!release.get() && go()) Thread.onSpinWait()
                RecoveryPlanner.plan(from, goal, depth, set, go)
            })
        open(cornerLesson.id.value, planRecovery = held)
        switchTo(TeachingMode.TEST_ME)
        disturbCross()
        node("lesson:recovery:guideMeBack").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { host.state.value.guidedRecovery is GuidedRecovery.Planning }
        rule.waitForIdle()
        node("lesson:guidedRecovery:pending")
            .performScrollTo()
            .assertTextContains("Finding the shortest way back…")
            .assert(hasAnyAncestor(hasTestTag("lesson:stepCard")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        node("lesson:recovery:guideMeBack").assertIsNotEnabled()

        release.set(true)
        rule.waitUntil(5_000) { host.state.value.guidedRecovery is GuidedRecovery.Planned }
        rule.waitForIdle()
        node("lesson:guidedRecovery:pending").assertDoesNotExist()
        node("lesson:guidedRecovery:plan")
            .performScrollTo()
            .assertTextContains("1 move — your cross stays", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule
            .onNode(
                hasTestTag("moveChip:R'") and hasAnyAncestor(hasTestTag("lesson:guidedRecovery:plan")),
                useUnmergedTree = true,
            ).assertExists()
        node("lesson:recovery:guideMeBack").assertIsNotEnabled()
        rule.onAllNodes(isDialog()).assertCountEquals(0)

        // The cross edges the plan keeps are outlined (not the disturbed pieces); no sticker is recolored.
        val planned = (host.state.value.guidedRecovery as GuidedRecovery.Planned).pieces
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val expected = (0 until Facelets.SIZE).filter { Piece.at(facelets, it) in planned }.map { "sticker:$it" }.toSet()
        assertEquals(8, expected.size)
        assertEquals(expected, stickersWith(StickerHighlightedKey))
        assertNetMatchesSession("the Learner's cube is drawn as it is")

        // The Learner turns the plan's Move; the line goes.
        press("R'", 2)
        rule.waitUntil(5_000) { host.state.value.guidedRecovery == GuidedRecovery.None }
        rule.waitForIdle()
        node("lesson:guidedRecovery:plan").assertDoesNotExist()
        assertEquals(host.state.value.entry, host.state.value.virtual)
    }

    @Test
    fun `a Move while the plan is pending stops the search and offers Guide me back again`() {
        val running = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val spinning =
            PlanRecovery({ _, _, _, _, go ->
                running.set(true)
                while (go()) Thread.onSpinWait()
                stopped.set(true)
                RecoveryPlan.Stopped
            })
        open(cornerLesson.id.value, planRecovery = spinning)
        switchTo(TeachingMode.TEST_ME)
        disturbCross()
        node("lesson:recovery:guideMeBack").performScrollTo().performClick()
        rule.waitUntil(2_000) { running.get() }
        rule.waitForIdle()
        node("lesson:guidedRecovery:pending").assertExists()

        // F disturbs another cross edge: the pending plan is cancelled well before its 3 s deadline.
        press("F", 2)
        rule.waitUntil(1_000) { stopped.get() }
        rule.waitForIdle()
        assertEquals(GuidedRecovery.None, host.state.value.guidedRecovery)
        assertTrue(
            host.state.value.decision
                ?.diagnosis != null,
        )
        node("lesson:guidedRecovery:pending").assertDoesNotExist()
        node("lesson:recovery:guideMeBack").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `guide me back with no plan says to undo or start the step again`() {
        open(cornerLesson.id.value, planRecovery = PlanRecovery({ _, _, _, _, _ -> RecoveryPlan.NotFound }))
        switchTo(TeachingMode.TEST_ME)
        disturbCross()
        node("lesson:recovery:guideMeBack").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.guidedRecovery == GuidedRecovery.NotFound }
        rule.waitForIdle()
        node("lesson:guidedRecovery:notFound")
            .performScrollTo()
            .assertTextContains("I can't find a short way back. Undo, or start this step again.")
        node("lesson:recovery:guideMeBack").assertIsNotEnabled()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun `outside Guide me the net is plain until the Objective-met pulse lights the target`() {
        open(whiteCrossLesson().id.value)
        switchTo(TeachingMode.LET_ME_TRY)
        assertTrue(stickersWith(StickerDimmedKey).isEmpty())
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())

        node("moveKey:F'").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.decision!!
                .satisfied
        }
        rule.mainClock.autoAdvance = false
        var pulsed = false
        repeat(PULSE_CHECKS) {
            if (!pulsed) {
                rule.mainClock.advanceTimeBy(PULSE_CHECK_MILLIS)
                pulsed = anyPulsing()
                if (pulsed) {
                    assertTrue(stickersWith(StickerHighlightedKey).containsAll(stickersWith(StickerPulsingKey)))
                    assertTrue("dimmed during the pulse", stickersWith(StickerDimmedKey).isEmpty())
                }
            }
        }
        assertTrue("satisfied=${host.state.value.decision} but no sticker pulsed", pulsed)
        rule.mainClock.advanceTimeBy(2_000)
        assertFalse(anyPulsing())
        assertTrue(stickersWith(StickerHighlightedKey).isEmpty())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun `the switcher shows only the modes it is given`() {
        open(whiteCrossLesson().id.value, modes = TeachingMode.entries - TeachingMode.TEST_ME)
        node("mode:TEST_ME").assertDoesNotExist()
        node("mode:LET_ME_TRY").assertExists()
    }

    private companion object {
        const val PULSE_CHECKS = 6
        const val PULSE_CHECK_MILLIS = 80L
        const val DEMO_CHECKS = 40
        const val DEMO_CHECK_MILLIS = 50L
    }

    /** The solved cube's drawn colors: the whiteCross step's F' played from its authored start (SOLVED + F). */
    private val solvedColors: List<Color> =
        dev.rubikxplorer.domain.cube.CubeState.SOLVED
            .toFacelets()
            .colors
            .map { Color(StickerAppearance.of(it).fill) }

    private val whiteCrossSolvingMove =
        FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)

    /** [times] mistakes on the corner Lesson's step in Test me: R disturbs the cross, Undo takes it back. */
    private fun tripUp(times: Int) {
        repeat(times) {
            runBlocking {
                host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
                host.dispatch(SessionEvent::Undo)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `with the flag on, two mistakes show why help changed, and back to my usual help hides the Moves again`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        node("lesson:assistance").assertDoesNotExist()
        tripUp(2)
        node("lesson:assistance").performScrollTo().assertTextContains("Showing the turn, since this step tripped you up twice.")
        node("lesson:assistance")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        node("lesson:assistance").assert(hasAnyAncestor(hasTestTag("lesson:stepCard")).not())
        node("lesson:assistance:usual").assertTextContains("Back to my usual help")
        node("lesson:assistance:beta").assertDoesNotExist()
        node("mode:TEST_ME").assertIsSelected()
        assertEquals(listOf("moveChip:R'", "moveChip:D'", "moveChip:R"), tagsStartingWith("moveChip:"))
        assertTrue("the target is outlined", stickersWith(StickerHighlightedKey).isNotEmpty())
        rule.onAllNodes(isDialog()).assertCountEquals(0)

        node("lesson:assistance:usual").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.assistanceDismissed
                .isNotEmpty()
        }
        rule.waitForIdle()
        node("lesson:assistance").assertDoesNotExist()
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
        node("lesson:hint").performScrollTo().assertTextContains("Hint (0/3)")
        node("mode:TEST_ME").assertIsSelected()
        assertEquals(TeachingMode.TEST_ME, host.state.value.teachingMode)
    }

    @Test
    fun `with the flag off, the same mistakes leave the Hint Ladder fixed and show no Assistance line`() {
        open(cornerLesson.id.value)
        switchTo(TeachingMode.TEST_ME)
        tripUp(3)
        node("lesson:assistance").assertDoesNotExist()
        node("lesson:hint").performScrollTo().assertTextContains("Hint (0/3)")
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    @Test
    fun `with the flag at BETA, the Assistance line carries the beta label`() {
        open(cornerLesson.id.value, adaptiveLevel = FlagLevel.BETA)
        switchTo(TeachingMode.TEST_ME)
        node("lesson:assistance:beta").assertDoesNotExist()
        tripUp(1)
        node("lesson:assistance").performScrollTo().assertTextContains("Pointing out the piece, since this step tripped you up.")
        node("lesson:assistance:beta").assertTextEquals("BETA")
        assertTrue(host.state.value.adaptiveAssistance)
    }

    @Test
    fun `at the guide level a half turn done as two quarters guides the second quarter, not Undo`() {
        open(crossLesson.id.value, adaptiveAssistance = true, thresholds = AdaptiveThresholds(0, 0, 0, 2))
        assertEquals(AssistanceLevel.GUIDE, host.state.value.assistance)
        assertEquals(listOf("moveChip:F2"), tagsStartingWith("moveChip:"))

        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.F, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        assertEquals(AssistanceLevel.GUIDE, host.state.value.assistance)
        assertEquals("the remaining quarter is guided", listOf("moveChip:F"), tagsStartingWith("moveChip:"))
        node("lesson:assistance").performScrollTo().assertTextContains("One turn at a time", substring = true)
        node("lesson:hint:line").performScrollTo().assertTextContains("The Moves are below.", substring = true)

        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.F, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        assertEquals("F then F completes step 1", 0, host.state.value.noticeStep)
    }

    @Test
    fun `continued difficulty shows only the next Move's chip`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        tripUp(3)
        node("lesson:assistance").performScrollTo().assertTextContains("One turn at a time, since this step tripped you up 3 times.")
        assertEquals(listOf("moveChip:R'"), tagsStartingWith("moveChip:"))
        node("mode:TEST_ME").assertIsSelected()
    }

    @Test
    fun `escalating to guide without undoing the last mistake says to Undo back before showing one Move`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        tripUp(2)
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        assertEquals(AssistanceLevel.GUIDE, host.state.value.assistance)
        node("lesson:assistance")
            .performScrollTo()
            .assertTextContains("You're off the path now — Undo back to it, and I'll show one turn at a time again.")
        assertEquals("off the path, the authored step's Moves are not shown", emptyList<String>(), tagsStartingWith("moveChip:"))
        assertEquals(HintRung.TARGET, host.state.value.shownRung)
        node("lesson:hint:line").assertDoesNotExist()

        runBlocking { host.dispatch(SessionEvent::Undo) }
        rule.waitForIdle()
        node("lesson:hint:line").performScrollTo().assertTextContains("The Moves are below.", substring = true)
        node("lesson:assistance").performScrollTo().assertTextContains("One turn at a time, since this step tripped you up 3 times.")
        assertEquals(listOf("moveChip:R'"), tagsStartingWith("moveChip:"))
        node("mode:TEST_ME").assertIsSelected()
    }

    /** Two mistakes on the corner Lesson's step, dispatched without waiting for idle, so a paused clock can watch what follows. */
    private fun tripUpTwiceWithPausedClock() {
        rule.mainClock.autoAdvance = false
        runBlocking {
            repeat(2) {
                host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) }
                host.dispatch(SessionEvent::Undo)
            }
        }
    }

    private fun sessionColors() =
        host.state.value.virtual
            .toFacelets()
            .colors
            .map { Color(StickerAppearance.of(it).fill) }

    @Test
    fun `escalating to demonstrate plays the step on the net once with no Hint tap and changes nothing`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        val demonstration = (Demonstration.of(cornerLesson) as DemonstrationResult.Ready).demonstration
        val endColors =
            demonstration.start
                .apply(demonstration.steps[0].moves)
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        tripUpTwiceWithPausedClock()
        val before = host.state.value
        assertEquals(AssistanceLevel.DEMONSTRATE, before.assistance)
        var played = false
        repeat(DEMO_CHECKS * 2) {
            if (!played) {
                rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
                played = drawnColors() == endColors
            }
        }
        assertTrue("the net never played the step without a Hint tap", played)
        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + REORIENT_STEP_MILLIS + 500)
        rule.mainClock.autoAdvance = true
        assertNetMatchesSession("net returns to the session after the demonstration")
        assertEquals(HintRung.NONE, host.state.value.hintRung)
        assertEquals(0, host.state.value.hintsUsed)
        assertEquals(TeachingMode.TEST_ME, host.state.value.teachingMode)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)

        // Recomposition and leaving and returning to the level do not replay it.
        switchTo(TeachingMode.SHOW_ME)
        switchTo(TeachingMode.TEST_ME)
        assertEquals(AssistanceLevel.DEMONSTRATE, host.state.value.assistance)
        rule.mainClock.autoAdvance = false
        repeat(DEMO_CHECKS) {
            rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
            assertEquals("the demonstration played again", sessionColors(), drawnColors())
        }
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun `a Move during the escalation demonstration stops it and the net shows the post-Move session`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        tripUpTwiceWithPausedClock()
        var running = false
        repeat(DEMO_CHECKS * 2) {
            if (!running) {
                rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
                running = drawnColors() != sessionColors()
            }
        }
        assertTrue("the escalation demonstration never started", running)
        // Scroll while the clock runs: a paused clock never ends the scroll.
        rule.mainClock.autoAdvance = true
        node("moveKey:L").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.done.size == 1 }
        assertNetMatchesSession("after a Move interrupts the demonstration")
        assertEquals(TeachingMode.TEST_ME, host.state.value.teachingMode)
    }

    @Test
    fun `repeated success reduces the Hint Ladder to two rungs`() {
        open(crossLesson.id.value, adaptiveAssistance = true)
        runBlocking {
            host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.F, Turn.HALF), it) }
            host.dispatch(SessionEvent::StepAdvanced)
            host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.HALF), it) }
            host.dispatch(SessionEvent::StepAdvanced)
        }
        rule.waitForIdle()
        node("lesson:assistance").performScrollTo().assertTextContains("Fewer hints now — you've got this step.")
        node("lesson:hint").performScrollTo().assertTextContains("Hint (0/2)").assertIsEnabled()
        tapHint(HintRung.CLUE)
        node("lesson:hint").assertTextContains("Hint (1/2)")
        tapHint(HintRung.TARGET)
        node("lesson:hint").assertTextContains("Hint (2/2)").assertIsNotEnabled()
        node("mode:GUIDE_ME").assertIsSelected()
    }

    @Test
    fun `learner summary follows committed Moves in all learner modes`() {
        open(cornerLesson.id.value)
        listOf(TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY).forEach { mode ->
            switchTo(mode)

            fun assertSummary() {
                val state = host.state.value.virtual
                val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
                val strings =
                    dev.rubikxplorer.app.content
                        .ContentStrings(bundle.strings)
                val expected =
                    dev.rubikxplorer.domain.predicate.CubeSummary
                        .of(state, cornerLesson.completion)
                        .joinToString("; ") { strings.resolve(it) }
                assertEquals(listOf(expected), node("cube3d").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
            }
            assertSummary()
            val revision = host.state.value.revision
            node("moveKey:F").performClick()
            rule.waitUntil(5_000) { host.state.value.revision > revision }
            rule.waitForIdle()
            assertSummary()
        }
    }

    @Test
    fun `platform traversal follows learner stage order in every learner mode on a wide screen`() {
        RuntimeEnvironment.setQualifiers("w1200dp-h1600dp")
        assertLearnerTraversal()
    }

    @Test
    fun `platform traversal follows learner stage order in every learner mode on a compact screen`() {
        // Tall enough that the scrolling panel shows whole; TalkBack only walks nodes on screen.
        RuntimeEnvironment.setQualifiers("w400dp-h2400dp")
        assertLearnerTraversal()
    }

    @Test
    fun `the corner lesson cube reads its Objective progress in words`() {
        open(cornerLesson.id.value)
        switchTo(TeachingMode.TEST_ME)

        fun spoken() = node("cube3d").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals("White cross solved; 3 of 4 first-layer corners placed", spoken())
        // L D L' takes the front-left white corner out of its slot and keeps the cross.
        press("L", 1)
        press("D", 2)
        press("L'", 3)
        assertEquals("White cross solved; 2 of 4 first-layer corners placed", spoken())
    }

    @Test
    fun `the rung 3 demonstration's held frame reads the shown cube's Objective progress, then the session's again`() {
        val demonstration = (Demonstration.of(cornerLesson) as DemonstrationResult.Ready).demonstration
        open(cornerLesson.id.value)
        switchTo(TeachingMode.TEST_ME)

        fun spoken() = node("cube3d").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        // L D L' takes the front-left white corner out of its slot, so the session's cube differs from the shown one.
        press("L", 1)
        press("D", 2)
        press("L'", 3)
        val session = spoken()
        tapHint(HintRung.CLUE)
        tapHint(HintRung.TARGET)
        val step = demonstration.steps[host.state.value.stepIndex]
        val end = demonstration.start.apply(demonstration.moves.take(step.firstMove)).apply(step.moves)
        val strings = (catalog.state.value as CatalogState.Ready).strings
        val expected =
            dev.rubikxplorer.domain.predicate.CubeSummary
                .of(end, cornerLesson.completion)
                .joinToString("; ") { strings.resolve(it) }
        assertNotEquals(session, expected)

        rule.mainClock.autoAdvance = false
        node("lesson:hint").performScrollTo().performClick()
        val endColors = end.toFacelets().colors.map { Color(StickerAppearance.of(it).fill) }
        var held = false
        repeat(DEMO_CHECKS) {
            if (!held) {
                rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
                held = drawnColors() == endColors
            }
        }
        assertTrue("the net never held the step's end", held)
        // Summary, Read faces and Solved all describe the presented cube at the held frame.
        rule.mainClock.advanceTimeBy(REORIENT_STEP_MILLIS)
        assertEquals(endColors, drawnColors())
        assertEquals(expected, spoken())

        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + REORIENT_STEP_MILLIS + 500)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNetMatchesSession("net returns to the session after the demonstration")
        assertEquals(session, spoken())
    }

    @Test
    fun `Show me reads the shown Demonstration state's Objective progress`() {
        open(cornerLesson.id.value)
        switchTo(TeachingMode.SHOW_ME)

        fun spoken() = node("cube3d").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals("White cross solved; 3 of 4 first-layer corners placed", spoken())
        node("playback:end").performClick()
        rule.waitForIdle()
        assertEquals("White cross solved; First-layer corners solved", spoken())
    }

    @Test
    fun `Show me offers Read faces on the cube and the Lesson stage's frame label`() {
        open(cornerLesson.id.value)
        switchTo(TeachingMode.SHOW_ME)
        val cube = node("cube3d").fetchSemanticsNode().config
        assertEquals("Read faces", cube[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single().label)
        val frame = node("stage:frame").fetchSemanticsNode().config
        node("stage:frame").assertTextEquals("VIRTUAL")
        assertEquals(LiveRegionMode.Polite, frame[SemanticsProperties.LiveRegion])
        val certainty =
            when (
                host.state.value.decision!!
                    .certainty
            ) {
                Certainty.VERIFIED -> "Verified"
                Certainty.LIKELY -> "Likely"
                Certainty.UNKNOWN -> "Unknown"
            }
        assertEquals(certainty, frame[SemanticsProperties.StateDescription])
    }

    @Test
    fun `platform traversal follows Show me stage order on a wide screen`() {
        RuntimeEnvironment.setQualifiers("w1200dp-h1600dp")
        assertStageTraversal(listOf(TeachingMode.SHOW_ME))
    }

    @Test
    fun `platform traversal follows Show me stage order on a compact screen`() {
        // Tall enough that the scrolling panel shows whole; TalkBack only walks nodes on screen.
        RuntimeEnvironment.setQualifiers("w400dp-h2400dp")
        assertStageTraversal(listOf(TeachingMode.SHOW_ME))
    }

    private fun assertLearnerTraversal() =
        assertStageTraversal(listOf(TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY))

    /**
     * Walks Compose's platform traversal chain (what TalkBack follows) in each of [modes] and checks
     * frame label → cube → Move key bar → stage toolbar → lesson panel → Teaching Mode switcher; Show me
     * has the playback controls where the Move key bar is and its view controls in the toolbar's place.
     */
    private fun assertStageTraversal(modes: List<TeachingMode>) {
        val app =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
            setEnabledAccessibilityServiceList(listOf(android.accessibilityservice.AccessibilityServiceInfo()))
        }
        open(cornerLesson.id.value)
        modes.forEach { mode ->
            switchTo(mode)
            val showMe = mode == TeachingMode.SHOW_ME
            val frame = node("stage:frame").fetchSemanticsNode().id
            val cube = node("cube3d").fetchSemanticsNode().id
            val move = node(if (showMe) "playback:playPause" else "moveKey:U").fetchSemanticsNode().id
            val undo = node(if (showMe) "stage:viewMenu" else "toolbar:undo").fetchSemanticsNode().id
            val objective = node("lesson:objective").fetchSemanticsNode().id
            val hint = node("lesson:hint").fetchSemanticsNode().id
            val next = node("lesson:next").fetchSemanticsNode().id
            val modeOption = node("mode:SHOW_ME").fetchSemanticsNode().id
            val chain =
                rule.runOnIdle {
                    val provider = accessibilityHost.accessibilityNodeProvider!!
                    val key = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
                    val result = mutableListOf<Int>()
                    var id = frame
                    while (id != -1 && id !in result) {
                        result += id
                        val info = provider.createAccessibilityNodeInfo(id) ?: break
                        provider.addExtraDataToAccessibilityNodeInfo(id, info, key, null)
                        id = info.extras.getInt(key, -1)
                    }
                    result
                }
            assertEquals("$mode: the cube follows the frame label directly: $chain", cube, chain.getOrNull(1))
            listOf(cube to move, move to undo, undo to objective, objective to hint, hint to next, next to modeOption)
                .forEach { (before, after) ->
                    assertTrue(
                        "$mode traversal $before before $after: $chain",
                        before in chain && after in chain && chain.indexOf(before) < chain.indexOf(after),
                    )
                }
        }
    }

    private fun openFromShell(adaptive: Boolean) {
        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(adaptive = adaptive),
                )
            }
        }
        node("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == whiteCrossLesson().id
        }
        rule.waitForIdle()
    }

    @Test
    fun `the shell starts a Lesson with adaptive assistance when its flag is on`() {
        openFromShell(adaptive = true)
        assertTrue(host.state.value.adaptiveAssistance)
    }

    @Test
    fun `the shell starts a Lesson without adaptive assistance when its flag is off`() {
        openFromShell(adaptive = false)
        assertFalse(host.state.value.adaptiveAssistance)
    }

    @Test
    fun `one mistake outlines exactly the step's target with no Move chips`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        tripUp(1)
        assertEquals(HintRung.NONE, host.state.value.hintRung)
        node("lesson:assistance").performScrollTo().assertTextContains("Pointing out the piece, since this step tripped you up.")
        val facelets =
            host.state.value.virtual
                .toFacelets()
        val expected =
            (0 until Facelets.SIZE)
                .filter { Piece.at(facelets, it) in HintLadder.highlight(cornerLesson, 0) }
                .map { "sticker:$it" }
                .toSet()
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, stickersWith(StickerHighlightedKey))
        assertEquals(emptyList<String>(), tagsStartingWith("moveChip:"))
    }

    @Test
    fun `one Hint from the demonstrate floor plays the step on the net, then shows the session again`() {
        open(cornerLesson.id.value, adaptiveAssistance = true)
        switchTo(TeachingMode.TEST_ME)
        tripUp(2)
        val before = host.state.value
        assertEquals(HintRung.NONE, before.hintRung)
        assertEquals(HintRung.TARGET, before.shownRung)
        assertNetMatchesSession("before the demonstration")

        val demonstration = (Demonstration.of(cornerLesson) as DemonstrationResult.Ready).demonstration
        val end = demonstration.start.apply(demonstration.steps[0].moves)
        val endColors = end.toFacelets().colors.map { Color(StickerAppearance.of(it).fill) }
        // Scroll while the clock runs: the Assistance line pushes Hint below the fold, and a paused clock never ends the scroll.
        node("lesson:hint").performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        node("lesson:hint").performClick()
        var played = false
        repeat(DEMO_CHECKS * 2) {
            if (!played) {
                rule.mainClock.advanceTimeBy(DEMO_CHECK_MILLIS)
                played = drawnColors() == endColors
            }
        }
        assertTrue("the net never showed the step played from its authored start", played)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)

        rule.mainClock.advanceTimeBy(REORIENT_HOLD_MILLIS + REORIENT_STEP_MILLIS + 500)
        rule.mainClock.autoAdvance = true
        assertNetMatchesSession("net returns to the session after the demonstration")
        assertEquals(HintRung.DEMONSTRATION, host.state.value.hintRung)
        assertEquals(1, host.state.value.hintsUsed)
        assertEquals(before.revision, host.state.value.revision)
        assertEquals(before.history, host.state.value.history)
    }
}
