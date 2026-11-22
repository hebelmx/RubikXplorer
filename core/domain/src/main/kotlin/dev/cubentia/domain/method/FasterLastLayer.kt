package dev.cubentia.domain.method

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.lastlayer.LastLayerReading
import dev.cubentia.domain.lastlayer.LastLayerStep
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.predicate.LastLayerStepReached

/** One Library [algorithm] of a last-layer set, its [moves] parsed verbatim (AD-7). */
internal class SetAlgorithm(
    val algorithm: Algorithm,
    val moves: List<Move>,
    val preferred: Boolean,
)

/** One look of a last layer: reach [step] with [algorithms], in content order. */
internal class Look(
    val step: LastLayerStep,
    val algorithms: List<SetAlgorithm>,
)

/** How building a faster last layer ended. */
internal sealed interface LastLayerBuild {
    /** The last-layer [segments] in the held frame (none when the last layer was already solved). */
    data class Done(
        val segments: List<MethodSegment>,
    ) : LastLayerBuild

    data object NotFound : LastLayerBuild

    data object Stopped : LastLayerBuild
}

/**
 * The 2-look and full OLL + PLL last layers (Story 15.2, AD-19, AD-21), built only from Library
 * Algorithms played verbatim with held `U` adjustments and judged only through `LastLayerMatcher`.
 *
 * Everything is in the held frame. One Cube Rotation brings [LAST_LAYER] to U first ([rotationToTop]);
 * then each look is reached by one or more applications (an optional `U` pre-turn and one Algorithm),
 * each its own segment tagged with the Algorithm's Case. 2-look searches each look breadth-first by
 * fewest applications; full recognises the Case with [LastLayerMatcher.candidates] and plays the Case's
 * one Algorithm. A closing `U` turn joins the last Case segment.
 */
internal object FasterLastLayer {
    /** The last-layer color: opposite WHITE. */
    val LAST_LAYER: Color = Color.YELLOW

    /** No turn, then each `U` turn: the pre-turns and closing turns, in search order. */
    private val U_TURNS: List<FaceTurn?> = listOf(null) + Turn.entries.map { FaceTurn(Face.U, it) }

    /** The one Cube Rotation that brings [LAST_LAYER] to held U keeping the front where possible, or null when it is there. */
    fun rotationToTop(orientation: Orientation): CubeRotation? =
        when (Face.entries.first { orientation.colorAt(it) == LAST_LAYER }) {
            Face.U -> null
            Face.D -> CubeRotation(Axis.Z, Turn.HALF)
            Face.L -> CubeRotation(Axis.Z, Turn.CLOCKWISE)
            Face.R -> CubeRotation(Axis.Z, Turn.COUNTERCLOCKWISE)
            Face.F -> CubeRotation(Axis.X, Turn.CLOCKWISE)
            Face.B -> CubeRotation(Axis.X, Turn.COUNTERCLOCKWISE)
        }

    /** True for a held `U` turn. */
    fun isUTurn(move: Move): Boolean = move is FaceTurn && move.face == Face.U

    private fun isPreferred(
        algorithm: Algorithm,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
    ): Boolean = statuses[algorithm.id].let { it == AlgorithmStatus.LEARNING || it == AlgorithmStatus.LEARNED }

    private fun parsed(
        algorithm: Algorithm,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
    ): SetAlgorithm? {
        val moves = (Notation.parse(algorithm.notation) as? NotationResult.Parsed)?.moves ?: return null
        if (moves.isEmpty()) return null
        return SetAlgorithm(algorithm, moves, isPreferred(algorithm, statuses))
    }

    /**
     * The looks of [method] from [bundle], or null when the content has none usable.
     * 2-look: each `bundle.twoLookStages` Lesson's completion step is a look holding that Lesson's
     * Algorithms; Lessons sharing a step share the look. Full: `ORIENTED` with one Algorithm per OLL
     * Case, then `SOLVED_UP_TO_AUF` with one per PLL Case: the first preferred, otherwise the primary.
     */
    fun looksOf(
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
    ): List<Look>? =
        when (method) {
            SolveMethod.BEGINNER -> null
            SolveMethod.TWO_LOOK -> twoLookLooks(bundle, statuses)
            SolveMethod.FULL_OLL_PLL -> fullLooks(bundle, statuses)
        }

    private fun twoLookLooks(
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
    ): List<Look>? {
        val library = bundle.algorithms.associateBy { it.id }
        val byStep = linkedMapOf<LastLayerStep, MutableList<SetAlgorithm>>()
        for (lesson in bundle.twoLookStages.flatMap { it.lessons }) {
            val step = (lesson.completion as? LastLayerStepReached)?.step ?: continue
            val look = byStep.getOrPut(step) { mutableListOf() }
            for (id in bundle.lessonAlgorithms[lesson.id].orEmpty()) {
                if (look.any { it.algorithm.id == id }) continue
                val algorithm = library[id] ?: return null
                look += parsed(algorithm, statuses) ?: return null
            }
        }
        val looks = byStep.map { (step, algorithms) -> Look(step, algorithms) }
        return looks.takeIf { it.isNotEmpty() && it.all { look -> look.algorithms.isNotEmpty() } }
    }

    private fun fullLooks(
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
    ): List<Look>? {
        fun lookOf(
            set: CaseSet,
            step: LastLayerStep,
        ): Look? {
            val algorithms =
                bundle.cases.filter { it.set == set }.map { case ->
                    val own = bundle.algorithms.filter { it.caseId == case.id }
                    val chosen = own.firstOrNull { isPreferred(it, statuses) } ?: own.firstOrNull { it.isPrimary } ?: return null
                    parsed(chosen, statuses) ?: return null
                }
            return if (algorithms.isEmpty()) null else Look(step, algorithms)
        }
        return listOf(
            lookOf(CaseSet.OLL, LastLayerStep.ORIENTED) ?: return null,
            lookOf(CaseSet.PLL, LastLayerStep.SOLVED_UP_TO_AUF) ?: return null,
        )
    }

    /**
     * The last-layer segments of [start], a held-frame state whose first two layers are solved, or
     * NotFound when a look cannot be reached within [MethodSolve.LL_MAX_APPLICATIONS].
     */
    fun build(
        start: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
        lastLayerStage: StageId,
        shouldContinue: () -> Boolean,
    ): LastLayerBuild {
        if (start.isSolved) return LastLayerBuild.Done(emptyList())
        val looks = looksOf(method, bundle, statuses) ?: return LastLayerBuild.NotFound
        val matcher =
            if (method == SolveMethod.FULL_OLL_PLL) {
                (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as? LastLayerMatcherResult.Built)?.matcher
                    ?: return LastLayerBuild.NotFound
            } else {
                null
            }
        val rotation = rotationToTop(start.orientation)
        var state = if (rotation == null) start else start.apply(rotation)
        val search = Search(shouldContinue)
        val applications = mutableListOf<Application>()
        for (look in looks) {
            if (!shouldContinue()) return LastLayerBuild.Stopped
            val reached = LastLayerStepReached(look.step)
            if (reached.isSatisfied(state)) continue
            val found =
                if (matcher != null) {
                    search.recognise(state, look, matcher, reached)
                } else {
                    search.preferredFirst(state, look, reached)
                }
            when (found) {
                is Found -> {
                    state = found.state
                    applications += found.applications
                }

                Missing -> {
                    return LastLayerBuild.NotFound
                }

                Halted -> {
                    return LastLayerBuild.Stopped
                }
            }
        }
        val closing = U_TURNS.firstOrNull { turn -> (if (turn == null) state else state.apply(turn)).isSolved }
        val solvedWithoutTurn = state.isSolved
        if (closing == null && !solvedWithoutTurn) return LastLayerBuild.NotFound
        val segments =
            applications.map { MethodSegment(SegmentTag.OfCase(it.algorithm.algorithm.caseId), listOfNotNull(it.pre) + it.algorithm.moves) }
        if (segments.isEmpty()) {
            // Only a closing turn: a lone AUF segment tagged with the method's last-layer Stage.
            return LastLayerBuild.Done(listOf(MethodSegment(SegmentTag.OfStage(lastLayerStage), listOfNotNull(rotation, closing))))
        }
        val result = segments.toMutableList()
        if (rotation != null) result[0] = result[0].copy(moves = listOf(rotation) + result[0].moves)
        if (closing != null) result[result.lastIndex] = result.last().copy(moves = result.last().moves + closing)
        return LastLayerBuild.Done(result)
    }

    /** One application: an optional held `U` [pre]-turn, then [algorithm]. */
    private class Application(
        val pre: FaceTurn?,
        val algorithm: SetAlgorithm,
    )

    private sealed interface LookBuild

    private class Found(
        val state: CubeState,
        val applications: List<Application>,
    ) : LookBuild

    private data object Missing : LookBuild

    private data object Halted : LookBuild

    private class Search(
        private val shouldContinue: () -> Boolean,
    ) {
        // An Algorithm's whole effect from one orientation: the cubies to compose and the orientation it leaves.
        private val effects = hashMapOf<Pair<SetAlgorithm, Orientation>, CubeState>()

        /** [state] after [algorithm], or null when the Algorithm would leave [LAST_LAYER] off held U. */
        fun applied(
            state: CubeState,
            algorithm: SetAlgorithm,
        ): CubeState? {
            val effect =
                effects.getOrPut(
                    algorithm to state.orientation,
                ) { CubeState.SOLVED.reoriented(state.orientation).apply(algorithm.moves) }
            if (effect.orientation.colorAt(Face.U) != LAST_LAYER) return null
            return state.copy(cubies = state.cubies.compose(effect.cubies), orientation = effect.orientation)
        }

        /** Searches with the look's preferred Algorithms first; when they cannot reach it, with all of them. */
        fun preferredFirst(
            start: CubeState,
            look: Look,
            reached: LastLayerStepReached,
        ): LookBuild {
            val preferred = look.algorithms.filter { it.preferred }
            if (preferred.isNotEmpty()) {
                when (val found = breadthFirst(start, preferred, reached)) {
                    is Found, Halted -> return found
                    Missing -> Unit
                }
            }
            return breadthFirst(start, look.algorithms, reached)
        }

        private class Node(
            val state: CubeState,
            val applications: List<Application>,
        )

        private fun breadthFirst(
            start: CubeState,
            algorithms: List<SetAlgorithm>,
            reached: LastLayerStepReached,
        ): LookBuild {
            val visited = hashSetOf(start.cubies)
            var frontier = listOf(Node(start, emptyList()))
            repeat(MethodSolve.LL_MAX_APPLICATIONS) {
                val next = mutableListOf<Node>()
                for (node in frontier) {
                    for (pre in U_TURNS) {
                        val turned = if (pre == null) node.state else node.state.apply(pre)
                        for (algorithm in algorithms) {
                            if (!shouldContinue()) return Halted
                            val after = applied(turned, algorithm) ?: continue
                            if (!visited.add(after.cubies)) continue
                            val applications = node.applications + Application(pre, algorithm)
                            if (reached.isSatisfied(after)) return Found(after, applications)
                            next += Node(after, applications)
                        }
                    }
                }
                frontier = next
            }
            return Missing
        }

        /** Recognises the Case of [state] among the look's Cases and plays its Algorithm after the first pre-turn that reaches the look. */
        fun recognise(
            state: CubeState,
            look: Look,
            matcher: LastLayerMatcher,
            reached: LastLayerStepReached,
        ): LookBuild {
            val view = (LastLayerView.of(state, LAST_LAYER) as? LastLayerReading.View)?.view ?: return Missing
            val candidates = matcher.candidates(view)
            for (algorithm in look.algorithms.filter { it.algorithm.caseId in candidates }) {
                for (pre in U_TURNS) {
                    if (!shouldContinue()) return Halted
                    val turned = if (pre == null) state else state.apply(pre)
                    val after = applied(turned, algorithm) ?: continue
                    if (reached.isSatisfied(after)) return Found(after, listOf(Application(pre, algorithm)))
                }
            }
            return Missing
        }
    }
}
