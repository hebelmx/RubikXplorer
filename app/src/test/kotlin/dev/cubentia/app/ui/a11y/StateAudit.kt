package dev.cubentia.app.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.R
import dev.cubentia.app.content.CatalogEntry
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.random.KotlinRandomSource
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.ui.editor.EditorFixtures
import dev.cubentia.app.ui.editor.ManualCubeEditor
import dev.cubentia.app.ui.lesson.LessonScreen
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.session.GuidedRecovery
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.usecase.StagePractice
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import dev.cubentia.domain.cube.Color as CubeColor

/**
 * AC2 at 200% type for the interaction states the screen walk in [auditScreens] does not reach: the
 * Lesson's Teaching modes, Hint rungs, Test me diagnosis, inline recovery and Guided recovery, the
 * adaptive Assistance line, the physical-cube confirm card, step controls, overflow menu, mismatch
 * ladder rungs 1–3, in-Lesson editor and verified recovery; the skip-check; 2-look recognition, its
 * Objective-met offer and failed write; the 2-look practice result; and the Manual editor with a
 * sticker selected, a colour painted and each kind of invalid-entry feedback. Every state is checked
 * for text overflow and 48dp clickables, and every Lesson state for the 280dp stage, a scrolling
 * panel whose last node can be reached and, when [expanded], a panel no wider than 560dp.
 */
internal fun auditInteractionStates(
    rule: ComposeContentTestRule,
    expanded: Boolean,
) {
    val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    val ready = CatalogState.Ready(bundle)
    var slot: @Composable () -> Unit by mutableStateOf({})
    var mount by mutableIntStateOf(0)
    rule.setContent { fontScale200 { CubentiaTheme { key(mount) { slot() } } } }

    fun show(content: @Composable () -> Unit) {
        rule.runOnIdle {
            slot = content
            mount++
        }
        rule.waitForIdle()
    }

    fun check(name: String) {
        rule.waitForIdle()
        rule.assertNoTextOverflow(name)
        rule.assertClickablesAtLeast48dp(name)
        if (rule.onAllNodesWithTag("lesson:stage").fetchSemanticsNodes().isNotEmpty()) {
            rule.assertLessonLayout(name, expanded)
        }
    }

    fun node(tag: String) = rule.onNodeWithTag(tag)

    fun open(
        host: SessionHost,
        entry: CatalogEntry,
        adaptive: FlagLevel = FlagLevel.OFF,
    ) {
        show {
            LessonScreen(host, entry, ready.strings, TeachingMode.entries, {}, adaptiveAssistance = adaptive)
        }
        rule.waitUntil(10_000) {
            host.state.value.lesson
                ?.id == entry.lesson.id &&
                rule.onAllNodesWithTag("lesson:panel").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    fun switchTo(
        host: SessionHost,
        mode: TeachingMode,
    ) {
        node("mode:${mode.name}").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.teachingMode == mode }
        rule.waitForIdle()
    }

    fun dispatch(
        host: SessionHost,
        event: (kotlin.time.Instant) -> SessionEvent,
    ) {
        runBlocking { host.dispatch(event) }
        rule.waitForIdle()
    }

    val corner = ready.lessons.single { it.lesson.id.value == "lesson-03-01" }
    val disturb = FaceTurn(Face.R, Turn.CLOCKWISE)

    // Virtual Lesson: every Teaching mode, each Hint rung, Test me diagnosis and both recovery lines.
    val virtual = SessionHost(TickingClock())
    open(virtual, corner)
    TeachingMode.entries.forEach { mode ->
        switchTo(virtual, mode)
        check("Lesson ${mode.name}")
    }
    switchTo(virtual, TeachingMode.TEST_ME)
    repeat(3) { rung ->
        node("lesson:hint").performScrollTo().performClick()
        rule.waitUntil(5_000) { virtual.state.value.hintsUsed == rung + 1 }
        node("lesson:hint:line").assertExists()
        check("Lesson Test me Hint ${rung + 1}")
    }
    dispatch(virtual) { SessionEvent.MoveCommitted(disturb, it) }
    rule.waitUntil(5_000) {
        virtual.state.value.decision
            ?.diagnosis != null
    }
    node("lesson:diagnosis").assertExists()
    node("lesson:recovery").assertExists()
    check("Lesson diagnosis with recovery")
    node("lesson:recovery:guideMeBack").performScrollTo().performClick()
    rule.waitUntil(10_000) {
        virtual.state.value.guidedRecovery
            .let { it is GuidedRecovery.Planned || it == GuidedRecovery.NotFound }
    }
    check("Lesson Guided recovery")

    // The adaptive Assistance line after two mistakes in Test me.
    val adaptive = SessionHost(TickingClock())
    open(adaptive, corner, FlagLevel.BETA)
    switchTo(adaptive, TeachingMode.TEST_ME)
    repeat(2) {
        dispatch(adaptive) { SessionEvent.MoveCommitted(disturb, it) }
        dispatch(adaptive, SessionEvent::Undo)
    }
    node("lesson:assistance").assertExists()
    check("Lesson Assistance line")

    // Physical-cube Mode: confirm card, step controls, overflow menu, the mismatch ladder, the
    // in-Lesson editor and the verified recovery offer.
    val physical = SessionHost(TickingClock())
    open(physical, corner)
    node("lesson:physicalModeSwitch").performClick()
    rule.waitUntil(5_000) { physical.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
    node("confirmCube:card").assertExists()
    check("Lesson physical confirm card")
    node("confirmCube:matches").performScrollTo().performClick()
    rule.waitUntil(5_000) { physical.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
    check("Lesson physical step controls")
    node("lesson:didSomethingElse").performScrollTo().performClick()
    check("Lesson physical Move keys")
    node("lesson:overflowMenu").performScrollTo().performClick()
    rule.waitForIdle()
    node("lesson:menuMismatch").assertExists()
    check("Lesson physical overflow menu")
    node("lesson:menuMismatch").performClick()
    rule.waitUntil(5_000) { physical.state.value.syncState == SyncState.PHYSICAL_MISMATCH }
    node("mismatch:ladder").assertExists()
    check("Lesson mismatch rung 1")
    node("mismatch:nextQuestion").performScrollTo().performClick()
    node("mismatch:corner:none").assertExists()
    check("Lesson mismatch rung 2")
    node("mismatch:nextQuestion").performScrollTo().performClick()
    node("mismatch:reenter").assertExists()
    check("Lesson mismatch rung 3")
    node("mismatch:reenter").performScrollTo().performClick()
    rule.waitForIdle()
    node("lesson:manualEditor").assertExists()
    check("Lesson physical editor")
    dispatch(physical) {
        SessionEvent.EditorEntered(
            physical.state.value.expected
                .apply(disturb),
            it,
        )
    }
    rule.waitUntil(5_000) { physical.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
    node("lesson:recovery:guideMeBack").assertExists()
    check("Lesson physical verified recovery")

    // The skip-check: no Teaching modes, its instruction above the panel.
    val skipHost = SessionHost(TickingClock())
    val skipEntry =
        CatalogEntry(StagePractice(KotlinRandomSource(Random(1))).courseSkipCheckLesson(), bundle.stages.last(), bundle.tutorConfig)
    show {
        LessonScreen(
            skipHost,
            skipEntry,
            ready.strings,
            emptyList(),
            {},
            adaptiveAssistance = FlagLevel.OFF,
            twoLookStages = bundle.twoLookStages,
            stages = bundle.stages,
            isSkipCheck = true,
            skipCheckInstruction = stringResource(R.string.skip_check_instruction),
            onSkipCheckPassed = {},
        )
    }
    rule.waitUntil(10_000) { rule.onAllNodesWithTag("lesson:panel").fetchSemanticsNodes().isNotEmpty() }
    node("lesson:skipCheckInstruction").assertExists()
    check("Skip-check")

    // 2-look: recognition, then the Objective-met offer, then a failed write keeping the offer.
    val twoLookEntry =
        ready.find(
            bundle.twoLookStages
                .first()
                .lessons
                .first()
                .id,
        )!!
    val ids = bundle.lessonAlgorithms.getValue(twoLookEntry.lesson.id)
    val diagram =
        (
            dev.cubentia.domain.lastlayer.RecognitionDiagram.derive(
                bundle.algorithms.single { it.id == ids.first() },
            ) as dev.cubentia.domain.lastlayer.RecognitionDiagramResult.Derived
        ).diagram
    val twoLook = SessionHost(TickingClock())
    runBlocking { twoLook.restore(bundle) }
    show {
        LessonScreen(
            twoLook,
            twoLookEntry,
            ready.strings,
            TeachingMode.entries,
            {},
            isTwoLook = true,
            recognition = diagram,
            offerAlgorithms = ids,
            onAddToLearning = { false },
        )
    }
    rule.waitUntil(10_000) {
        twoLook.state.value.kind == SessionKind.TWO_LOOK &&
            rule.onAllNodesWithTag("lesson:panel").fetchSemanticsNodes().isNotEmpty()
    }
    node("lesson:recognition").assertExists()
    check("2-look recognition")
    moves(twoLookEntry.lesson.demonstration).forEach { move -> dispatch(twoLook) { SessionEvent.MoveCommitted(move, it) } }
    node("lesson:twoLookOffer").assertExists()
    check("2-look offer")
    node("lesson:twoLookOffer:add").performScrollTo().performClick()
    rule.waitUntil(5_000) { rule.onAllNodesWithTag("lesson:twoLookOffer:failed").fetchSemanticsNodes().isNotEmpty() }
    check("2-look offer failed")

    // 2-look practice: the solved result line.
    val authored = bundle.twoLookStages.first().lessons[1]
    val practiceEntry =
        CatalogEntry(
            authored.copy(id = LessonId("practice-two-look"), completion = CubeSolved),
            bundle.twoLookStages.first(),
            bundle.tutorConfig,
        )
    val algorithms =
        bundle.lessonAlgorithms.values
            .flatten()
            .toSet()
            .map { id -> moves(bundle.algorithms.single { it.id == id }.notation) }
    val practice = SessionHost(TickingClock())
    runBlocking { practice.restore(bundle) }
    show {
        LessonScreen(
            practice,
            practiceEntry,
            ready.strings,
            emptyList(),
            {},
            isPractice = true,
            twoLookPractice = true,
            twoLookAlgorithms = algorithms,
            twoLookStages = bundle.twoLookStages,
        )
    }
    rule.waitUntil(10_000) { practice.state.value.kind == SessionKind.PRACTICE }
    rule.waitForIdle()
    moves(authored.demonstration).forEach { move -> dispatch(practice) { SessionEvent.MoveCommitted(move, it) } }
    node("lesson:twoLookResult").assertExists()
    check("2-look practice result")
}

/**
 * The Manual editor at 200% through its interaction states: a sticker selected, a colour painted
 * (wrong counts), an impossible corner outlined, and the twisted/flipped and parity lines.
 */
internal fun auditEditorStates(rule: ComposeContentTestRule) {
    val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    val strings = CatalogState.Ready(bundle).strings
    var initial by mutableStateOf(CubeState.SOLVED.toFacelets())
    rule.setContent { fontScale200 { CubentiaTheme { ManualCubeEditor(strings = strings, onAccept = {}, initial = initial) } } }

    fun check(name: String) {
        rule.waitForIdle()
        rule.assertNoTextOverflow(name)
        rule.assertClickablesAtLeast48dp(name)
    }

    fun paint(
        index: Int,
        color: CubeColor,
    ) {
        // The compact editor shows one face (tagged by its index, nine stickers each) at a time; the wide one shows the whole net.
        val face = index / 9
        if (rule.onAllNodesWithTag("editor:face:$face").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("editor:face:$face").performClick()
        }
        rule.onNodeWithTag("sticker:$index").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("swatch:${color.name}").performScrollTo().performClick()
        rule.waitForIdle()
    }

    rule.onNodeWithTag("sticker:0").performScrollTo().performClick()
    check("Manual editor sticker selected")
    rule.onNodeWithTag("swatch:${CubeColor.RED.name}").performScrollTo().performClick()
    rule.onNodeWithTag("editor:problem:0").assertExistsAfterIdle(rule)
    check("Manual editor wrong counts")
    paint(0, CubeColor.WHITE)
    paint(18, CubeColor.WHITE)
    paint(8, CubeColor.GREEN)
    rule.onNodeWithTag("editor:problem:0").assertExistsAfterIdle(rule)
    check("Manual editor impossible corner")
    listOf(EditorFixtures.twistedAndFlipped, EditorFixtures.swappedEdges).forEachIndexed { i, facelets ->
        rule.runOnIdle { initial = facelets }
        rule.onNodeWithTag("editor:problem:0").assertExistsAfterIdle(rule)
        check("Manual editor invalid entry ${i + 1}")
    }
    rule.runOnIdle { initial = Facelets(CubeState.SOLVED.toFacelets().colors) }
    check("Manual editor ready to accept")
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAfterIdle(rule: ComposeContentTestRule) {
    rule.waitForIdle()
    assertExists()
}

private fun moves(notation: String) = (Notation.parse(notation) as NotationResult.Parsed).moves
