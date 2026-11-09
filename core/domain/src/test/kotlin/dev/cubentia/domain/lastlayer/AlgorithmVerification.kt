package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation

/**
 * The FR-18 gate: one code path for the shipped bundle and the mistyped fixtures. For each Algorithm,
 * its Case state keeps the first two layers, matches its own Case and no other, is solved up to AUF by
 * the Algorithm, and has the signature pinned in `case-signatures.txt`.
 */
internal object AlgorithmVerification {
    private const val GOLDEN = "lastlayer/case-signatures.txt"

    fun shippedBundle(source: ContentSource = ContentSource.SHIPPED): ContentBundle {
        val result = ContentLoader.load(source)
        check(result is ContentLoadResult.Loaded) { "content did not load: $result" }
        return result.bundle
    }

    /** One line per failure, each starting with the Algorithm or Case it names; empty when verified. */
    fun problems(
        cases: List<Case>,
        algorithms: List<Algorithm>,
    ): List<String> {
        val invalid = (LastLayerMatcher.of(cases, algorithms) as? LastLayerMatcherResult.Invalid)?.problems.orEmpty()
        val brokenCases = invalid.map { problem -> caseOf(problem, algorithms) }.toSet()
        val healthy = cases.filter { it.id.value !in brokenCases }
        val built = LastLayerMatcher.of(healthy, algorithms) as LastLayerMatcherResult.Built
        val setOf = healthy.associate { it.id to it.set }
        val golden = goldenSignatures()
        return invalid.map(::describe) +
            algorithms.filter { it.caseId in setOf }.flatMap { algorithm ->
                problemsOf(algorithm, setOf.getValue(algorithm.caseId), built.matcher, golden)
            }
    }

    /** Throws an [AssertionError] listing every failing Algorithm of [bundle]. */
    fun assertVerified(bundle: ContentBundle) {
        val problems = problems(bundle.cases, bundle.algorithms)
        if (problems.isNotEmpty()) {
            throw AssertionError("Algorithm verification failed:\n" + problems.joinToString("\n"))
        }
    }

    private fun problemsOf(
        algorithm: Algorithm,
        set: CaseSet,
        matcher: LastLayerMatcher,
        golden: Map<String, String>,
    ): List<String> {
        val id = algorithm.id.value
        val state =
            when (val derived = LastLayerMatcher.caseState(algorithm)) {
                is CaseStateResult.BadNotation -> return listOf("$id: bad Notation ${derived.error}")
                is CaseStateResult.Derived -> derived.state
            }
        val reading = LastLayerView.of(state, LastLayerMatcher.CASE_LAST_LAYER)
        if (reading !is LastLayerReading.View) return listOf("$id: disturbs the first two layers")
        val problems = mutableListOf<String>()
        if (state.orientation != Orientation.HOME) problems += "$id: ends in a net rotation"
        val candidates = matcher.candidates(reading.view)
        if (candidates != setOf(algorithm.caseId)) problems += "$id: matches $candidates, not only ${algorithm.caseId}"
        val moves = (Notation.parse(algorithm.notation) as NotationResult.Parsed).moves
        val after = LastLayerView.of(state.apply(moves), LastLayerMatcher.CASE_LAST_LAYER)
        if (after !is LastLayerReading.View || !matcher.isSolvedUpToAuf(after.view)) {
            problems += "$id: does not solve the last layer up to AUF"
        }
        val signature = LastLayerMatcher.signature(reading.view, set)
        val expected = golden[algorithm.caseId.value]
        if (signature != expected) problems += "$id: signature $signature differs from the golden $expected"
        return problems
    }

    private fun caseOf(
        problem: LastLayerProblem,
        algorithms: List<Algorithm>,
    ): String =
        when (problem) {
            is LastLayerProblem.NoPrimaryAlgorithm -> problem.caseId.value
            is LastLayerProblem.BadAlgorithmNotation -> algorithms.first { it.id == problem.algorithmId }.caseId.value
            is LastLayerProblem.LeavesFirstTwoLayers -> algorithms.first { it.id == problem.algorithmId }.caseId.value
        }

    private fun describe(problem: LastLayerProblem): String =
        when (problem) {
            is LastLayerProblem.NoPrimaryAlgorithm -> "${problem.caseId}: has no primary Algorithm"
            is LastLayerProblem.BadAlgorithmNotation -> "${problem.algorithmId}: bad Notation ${problem.error}"
            is LastLayerProblem.LeavesFirstTwoLayers -> "${problem.algorithmId}: disturbs the first two layers"
        }

    private fun goldenSignatures(): Map<String, String> =
        checkNotNull(javaClass.classLoader.getResourceAsStream(GOLDEN)) { "missing $GOLDEN" }
            .bufferedReader()
            .useLines { lines -> lines.filter { it.isNotBlank() }.map { it.substringBefore(' ') to it.substringAfter(' ') }.toMap() }
}
