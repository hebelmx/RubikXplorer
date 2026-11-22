package dev.cubentia.app.ui.solve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.solver.MethodSolutionCache
import dev.cubentia.app.solver.MethodSolverFactory
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.MethodSolveOutcome
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.method.MethodSegment
import dev.cubentia.domain.method.SegmentTag
import dev.cubentia.domain.method.SolveMethod
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.MasteryAttempt
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.TwoLookMastery
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A *Solution style* in Solution Playback (FR-30, Story 15.3): the Solver's shortest solution or a method-shaped one. */
enum class SolutionStyle(
    val method: SolveMethod?,
    val label: String,
) {
    SHORTEST(null, "msg.playback.style.shortest"),
    BEGINNER(SolveMethod.BEGINNER, "msg.playback.style.beginner"),
    TWO_LOOK(SolveMethod.TWO_LOOK, "msg.playback.style.two-look"),
    FULL_OLL_PLL(SolveMethod.FULL_OLL_PLL, "msg.playback.style.full"),
}

private val SKIPPED = setOf(StageStatus.SKIPPED, StageStatus.SKIPPED_BY_PLACEMENT)
private val FINISHED = SKIPPED + setOf(StageStatus.COMPLETED, StageStatus.MASTERED)

/**
 * The method the Learner's progress suggests: *Full OLL + PLL* once 2-look is mastered or both 2-look Stages were
 * skipped; *2-look* once *Faster last layer* is unlocked (every Course Stage finished); *Beginner* otherwise.
 */
fun suggestedStyle(
    bundle: ContentBundle,
    progressEvents: List<ProgressEvent>,
    attempts: List<MasteryAttempt>,
): SolutionStyle {
    val all = bundle.stages + bundle.twoLookStages
    val mastered =
        TwoLookMastery.evaluate(bundle.twoLookStages, attempts, progressEvents, bundle.tutorConfig.twoLookSolvesRequired).mastered
    val twoLookSkipped =
        bundle.twoLookStages.isNotEmpty() &&
            bundle.twoLookStages.all { StageProgress.status(it, all, progressEvents) in SKIPPED }
    if (mastered || twoLookSkipped) return SolutionStyle.FULL_OLL_PLL
    val unlocked =
        bundle.stages.isNotEmpty() &&
            bundle.stages.all { StageProgress.status(it, bundle.stages, progressEvents) in FINISHED }
    return if (unlocked) SolutionStyle.TWO_LOOK else SolutionStyle.BEGINNER
}

/**
 * The segment labels of a method solution (AD-20): where each segment ends in [moves] and the content name of the
 * Stage or Case it practices. Used only while [moves] are the ones being played.
 */
data class SegmentLabels(
    val moves: List<Move>,
    val ends: List<Int>,
    val labels: List<Text?>,
) {
    /** The label of the segment holding the Move at [index]; null with no current step. */
    fun at(index: Int?): Text? {
        if (index == null || index < 0) return null
        val segment = ends.indexOfFirst { index < it }
        return if (segment < 0) null else labels[segment]
    }
}

/** The [SegmentLabels] of [segments] from [bundle]'s Stage titles and Case names; null for none. */
fun segmentLabels(
    segments: List<MethodSegment>,
    bundle: ContentBundle,
): SegmentLabels? {
    if (segments.isEmpty()) return null
    val stages = bundle.stages + bundle.twoLookStages
    var end = 0
    val ends = segments.map { segment -> (end + segment.moves.size).also { end = it } }
    val labels =
        segments.map { segment ->
            when (val tag = segment.tag) {
                is SegmentTag.OfStage -> stages.firstOrNull { it.id == tag.stageId }?.title
                is SegmentTag.OfCase -> bundle.cases.firstOrNull { it.id == tag.caseId }?.name
            }?.let { Text(it.value) }
        }
    return SegmentLabels(segments.flatMap { it.moves }, ends, labels)
}

/**
 * What the *Solution style* control shows: the [selected] chip, the style [playing], the style [building] (its
 * `Effect.MethodSolve` pending), a timeout or failure [message], and the [segments] of the method solution playing.
 */
data class SolutionStyleState(
    val selected: SolutionStyle = SolutionStyle.SHORTEST,
    val playing: SolutionStyle = SolutionStyle.SHORTEST,
    val building: SolutionStyle? = null,
    val message: Text? = null,
    val segments: List<MethodSegment> = emptyList(),
)

/**
 * The *Solution style* of one Solution Playback (Story 15.3, FR-30). A method solution is built only when its chip is
 * tapped, by an `Effect.MethodSolve` run through `SolveByMethod` (never the Solver, re-verified: AD-21), and kept in
 * [cache] for the app process. A loaded solution restarts the `SOLVE_PLAYBACK` session at step 1 through the
 * caller's `restart` (AD-3); a result whose session revision moved on meanwhile is discarded. Style, build and
 * segments are ephemeral, like the playback position (AD-20). Use from the main thread.
 */
@HiltViewModel
class SolutionStyleViewModel
    @Inject
    constructor(
        private val factory: MethodSolverFactory,
        private val cache: MethodSolutionCache,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(SolutionStyleState())
        val state: StateFlow<SolutionStyleState> = mutableState.asStateFlow()

        /** The cube the playback's solutions start from; null when no playback is open. */
        var input: CubeState? = null
            private set

        /** Whether [input] is an accepted scan, the Full verified baseline (kept on a restart). */
        var fromAcceptedScan: Boolean = false
            private set

        private var shortest: List<Move> = emptyList()
        private var build: Job? = null

        /** The pending build's Effect. */
        private var pending: Effect.MethodSolve? = null
        private var effectIds = 0L

        /** A new playback of [shortest] from [input] (a Solve tab solve or a *Use my cube* re-solve): back to *Shortest*. */
        fun opened(
            input: CubeState,
            shortest: List<Move>,
            fromAcceptedScan: Boolean,
        ) {
            cancelBuild()
            this.input = input
            this.shortest = shortest.toList()
            this.fromAcceptedScan = fromAcceptedScan
            mutableState.value = SolutionStyleState()
        }

        /** Playback closed: any build is cancelled and every message cleared. */
        fun left() {
            cancelBuild()
            input = null
            shortest = emptyList()
            fromAcceptedScan = false
            mutableState.value = SolutionStyleState()
        }

        /** The session moved to [revision]: a build issued at another revision is stale and cancelled (AD-3). */
        fun sessionMoved(revision: Long) {
            val issued = pending ?: return
            if (revision == issued.revision) return
            cancelBuild()
            mutableState.update { it.copy(selected = it.playing, building = null) }
        }

        /**
         * The Learner tapped [style]. [revision] reads the session's current revision; [restart] starts the
         * `SOLVE_PLAYBACK` session again on the given Moves from [input].
         */
        fun choose(
            style: SolutionStyle,
            bundle: ContentBundle,
            revision: () -> Long,
            restart: suspend (List<Move>) -> Unit,
        ) {
            val from = input ?: return
            val current = mutableState.value
            if (style == current.building) {
                mutableState.value = current.copy(message = null)
                return
            }
            cancelBuild()
            if (style == current.playing) {
                mutableState.value = current.copy(selected = style, building = null, message = null)
                return
            }
            val method = style.method
            if (method == null) {
                load(SolutionStyle.SHORTEST, emptyList(), shortest, restart)
                return
            }
            cache[from.toFacelets(), method]?.let { segments ->
                load(style, segments, segments.flatMap { it.moves }, restart)
                return
            }
            val effect = Effect.MethodSolve(++effectIds, revision(), from, method)
            pending = effect
            mutableState.value = current.copy(selected = style, building = style, message = null)
            build =
                viewModelScope.launch {
                    val completed = factory.create(bundle)(effect)
                    if (pending != effect) return@launch
                    pending = null
                    build = null
                    if (revision() != completed.revision) {
                        mutableState.update { it.copy(selected = it.playing, building = null) }
                        return@launch
                    }
                    when (val outcome = completed.outcome) {
                        is MethodSolveOutcome.Solved -> {
                            if (outcome.segments.isEmpty()) {
                                fallBack(failed(style), restart)
                            } else {
                                cache[from.toFacelets(), method] = outcome.segments
                                load(style, outcome.segments, outcome.segments.flatMap { it.moves }, restart)
                            }
                        }

                        MethodSolveOutcome.TimedOut -> {
                            fallBack(Text("msg.playback.style.timed-out"), restart)
                        }

                        MethodSolveOutcome.Failed -> {
                            fallBack(failed(style), restart)
                        }
                    }
                }
        }

        private fun failed(style: SolutionStyle): Text = Text("msg.playback.style.failed", mapOf("method" to Text(style.label)))

        /** Plays [moves] as [style] from step 1. */
        private fun load(
            style: SolutionStyle,
            segments: List<MethodSegment>,
            moves: List<Move>,
            restart: suspend (List<Move>) -> Unit,
        ) {
            mutableState.value = SolutionStyleState(selected = style, playing = style, segments = segments)
            // Not the build's job: a later tap must not cut a restart short between its events.
            viewModelScope.launch { restart(moves) }
        }

        /** *Shortest* is kept: back to it with [message], restarting only when a method solution was playing. */
        private fun fallBack(
            message: Text,
            restart: suspend (List<Move>) -> Unit,
        ) {
            val wasPlaying = mutableState.value.playing
            mutableState.value = SolutionStyleState(message = message)
            if (wasPlaying != SolutionStyle.SHORTEST) viewModelScope.launch { restart(shortest) }
        }

        private fun cancelBuild() {
            pending = null
            build?.cancel()
            build = null
        }
    }
