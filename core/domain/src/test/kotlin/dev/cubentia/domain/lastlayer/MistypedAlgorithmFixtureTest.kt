package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 9.2 AC 2: the gate that guards shipped content rejects each kind of wrong Algorithm. */
class MistypedAlgorithmFixtureTest {
    private val shipped = AlgorithmVerification.shippedBundle()

    private val mistyped: ContentSource =
        ContentSource.classpath("content-fixtures/mistyped-algorithm").let { fixture ->
            ContentSource { path -> fixture.read(path) ?: ContentSource.SHIPPED.read(path) }
        }

    private fun ContentBundle.notationOf(id: String): String = algorithms.first { it.id == AlgorithmId(id) }.notation

    private fun withNotations(vararg changes: Pair<String, String>): List<Algorithm> {
        val byId = changes.toMap()
        return shipped.algorithms.map { algorithm -> byId[algorithm.id.value]?.let { algorithm.copy(notation = it) } ?: algorithm }
    }

    private fun problems(algorithms: List<Algorithm>): List<String> = AlgorithmVerification.problems(shipped.cases, algorithms)

    @Test
    fun `the mistyped fixture still loads`() {
        assertEquals(78, AlgorithmVerification.shippedBundle(mistyped).algorithms.size)
    }

    @Test
    fun `the gate throws an AssertionError naming the mistyped Algorithm`() {
        val error =
            assertThrows(AssertionError::class.java) {
                AlgorithmVerification.assertVerified(AlgorithmVerification.shippedBundle(mistyped))
            }
        assertTrue("alg-pll-t-a" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a typo that disturbs the first two layers is reported by the matcher and the gate`() {
        val algorithms = withNotations("alg-pll-t-a" to "R U R'")
        val result = LastLayerMatcher.of(shipped.cases, algorithms)
        assertEquals(
            LastLayerMatcherResult.Invalid(listOf(LastLayerProblem.LeavesFirstTwoLayers(AlgorithmId("alg-pll-t-a")))),
            result,
        )
        assertEquals(listOf("alg-pll-t-a: disturbs the first two layers"), problems(algorithms))
    }

    @Test
    fun `a typo landing on another Case leaves two candidates and fails the gate`() {
        val algorithms = withNotations("alg-pll-t-a" to shipped.notationOf("alg-pll-y-a"))
        val matcher = (LastLayerMatcher.of(shipped.cases, algorithms) as LastLayerMatcherResult.Built).matcher
        val state = (LastLayerMatcher.caseState(algorithms.first { it.id == AlgorithmId("alg-pll-t-a") }) as CaseStateResult.Derived).state
        val view = (LastLayerView.of(state, LastLayerMatcher.CASE_LAST_LAYER) as LastLayerReading.View).view

        assertEquals(setOf("case-pll-t", "case-pll-y"), matcher.candidates(view).map { it.value }.toSet())
        val problems = problems(algorithms)
        assertTrue(problems.any { it.startsWith("alg-pll-t-a: matches") }, problems.toString())
    }

    @Test
    fun `two swapped Algorithms mismatch the golden signature for both`() {
        val problems =
            problems(
                withNotations(
                    "alg-pll-t-a" to shipped.notationOf("alg-pll-y-a"),
                    "alg-pll-y-a" to shipped.notationOf("alg-pll-t-a"),
                ),
            )
        assertEquals(2, problems.size, problems.toString())
        listOf("alg-pll-t-a", "alg-pll-y-a").forEach { id ->
            assertTrue(problems.any { it.startsWith("$id: signature") }, problems.toString())
        }
    }

    @Test
    fun `an Algorithm ending in a net rotation is reported`() {
        val problems =
            problems(
                withNotations(
                    "alg-pll-aa-a" to shipped.notationOf("alg-pll-aa-a").removePrefix("x "),
                    "alg-pll-v-a" to shipped.notationOf("alg-pll-v-a").removeSuffix("y'") + "y",
                ),
            )
        listOf("alg-pll-aa-a", "alg-pll-v-a").forEach { id ->
            assertTrue("$id: ends in a net rotation" in problems, problems.toString())
        }
    }

    @Test
    fun `every underivable Case is reported together`() {
        val algorithms = withNotations("alg-pll-t-a" to "R U R'", "alg-pll-y-a" to "F")
        assertEquals(
            LastLayerMatcherResult.Invalid(
                listOf("alg-pll-t-a", "alg-pll-y-a").map { LastLayerProblem.LeavesFirstTwoLayers(AlgorithmId(it)) },
            ),
            LastLayerMatcher.of(shipped.cases, algorithms),
        )
        val problems = problems(algorithms)
        listOf("alg-pll-t-a", "alg-pll-y-a").forEach { id ->
            assertTrue(problems.any { it.startsWith("$id:") }, problems.toString())
        }
    }

    @Test
    fun `the matcher result for a shipped bundle is Built`() {
        assertInstanceOf(LastLayerMatcherResult.Built::class.java, LastLayerMatcher.of(shipped.cases, shipped.algorithms))
    }
}
