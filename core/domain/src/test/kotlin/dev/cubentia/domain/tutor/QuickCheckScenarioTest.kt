package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.PhysicalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class QuickCheckScenarioTest {
    private fun partial(
        state: CubeState,
        faces: Set<Face>,
    ): PhysicalObservation.Partial {
        val colors = state.reoriented(Orientation.HOME).toFacelets().colors
        return PhysicalObservation.Partial(faces.associateWith { colors.subList(it.ordinal * 9, it.ordinal * 9 + 9) })
    }

    @Test fun `empty partial gives unknown without an expected match`() {
        val result =
            Assess.check(
                CandidateEnumerator.of(dev.cubentia.domain.cube.CubeState.SOLVED, TutorFixtures.CONFIG),
                PhysicalObservation.Partial(emptyMap()),
                emptySet(),
            )
        assertEquals(Certainty.UNKNOWN, result.certainty)
        assertEquals(false, result.matchesExpected)
        assertEquals(emptyList<Any>(), result.consistent)
    }

    @Test fun `seeded physical observations never falsely verify`() {
        val random = Random(106)
        val turns = TutorFixtures.CONFIG.moves.filterIsInstance<FaceTurn>()
        val counts = mutableMapOf<Certainty, Int>()
        var falseVerified = 0
        var matchingPlans = 0
        var confirmedPlans = 0
        var verifiedMatches = 0
        var verifiedMismatches = 0
        repeat(2100) { scenario ->
            val start =
                CubeState.SOLVED
                    .apply(
                        List(20) {
                            turns.random(random)
                        },
                    ).apply(CubeRotation(Axis.entries.random(random), Turn.entries.random(random)))
            val confirmed = List(random.nextInt(1, 4)) { turns.random(random) }
            val expected = start.apply(confirmed)
            val actualMoves = confirmed.toMutableList<Move>()
            val index = random.nextInt(confirmed.size)
            when (scenario % 6) {
                1 -> actualMoves[index] = confirmed[index].inverse()
                2 -> actualMoves[index] = confirmed[index].copy(face = Face.entries.filter { it != confirmed[index].face }.random(random))
                3 -> actualMoves += turns.random(random)
                4 -> actualMoves.removeAt(index)
                5 -> actualMoves += List(3) { turns.random(random) }
            }
            val actual = start.apply(actualMoves)
            val mustCover = if (random.nextBoolean()) Coverage.touched(start, confirmed) else Face.entries.toSet()
            val plan = QuickCheck.faces(mustCover)
            val enumerator = CandidateEnumerator.of(expected, TutorFixtures.CONFIG)
            val observations =
                listOf(
                    partial(actual, plan),
                    partial(
                        actual,
                        Face.entries
                            .shuffled(random)
                            .take(random.nextInt(1, 3))
                            .toSet(),
                    ),
                    PhysicalObservation.Full(actual),
                )
            observations.forEachIndexed { observationIndex, observation ->
                val check = Assess.check(enumerator, observation, mustCover)
                if (observation is PhysicalObservation.Partial && check.certainty == Certainty.VERIFIED) {
                    assertTrue(
                        check.consistent
                            .single()
                            .moves
                            .isEmpty(),
                    )
                }
                counts[check.certainty] = counts.getOrDefault(check.certainty, 0) + 1
                if (check.certainty == Certainty.VERIFIED) {
                    if (check.consistent
                            .single()
                            .state.cubies != actual.cubies
                    ) {
                        falseVerified++
                        println(
                            "False verification: scenario=$scenario perturbation=${scenario % 6} observation=$observationIndex mustCover=$mustCover plan=$plan",
                        )
                        println("start=${start.reoriented(Orientation.HOME).toFacelets().colors}; orientation=${start.orientation}")
                        println("confirmed=$confirmed; actualMoves=$actualMoves; candidate=${check.consistent.single().moves}")
                        println("actual=${actual.reoriented(Orientation.HOME).toFacelets().colors}")
                        println("candidate=${check.consistent.single().state.reoriented(Orientation.HOME).toFacelets().colors}")
                    }
                    if (check.matchesExpected) verifiedMatches++ else verifiedMismatches++
                }
                if (observationIndex == 0 && actual.cubies == expected.cubies) {
                    matchingPlans++
                    if (check.certainty == Certainty.VERIFIED) confirmedPlans++
                }
            }
        }
        println(
            "Quick Check: $falseVerified false verified; matching plan confirmation $confirmedPlans/$matchingPlans (${100.0 * confirmedPlans / matchingPlans}%)",
        )
        assertEquals(0, falseVerified)
        assertTrue(matchingPlans > 0)
        assertTrue(confirmedPlans.toDouble() / matchingPlans >= 0.98)
        assertTrue(verifiedMatches > 0)
        assertTrue(verifiedMismatches > 0)
        Certainty.entries.forEach { assertTrue(counts.getOrDefault(it, 0) > 0, "$it missing") }
    }

    @Test fun `unchanged observed faces never verify within the candidate model`() {
        val expected = CubeState.SOLVED
        val down = expected.apply(FaceTurn(Face.D, Turn.CLOCKWISE))
        assertEquals(
            Certainty.LIKELY,
            Assess.check(CandidateEnumerator.of(expected, TutorFixtures.CONFIG), partial(down, setOf(Face.U)), setOf(Face.U)).certainty,
        )
        val hidden = expected.apply(FaceTurn(Face.U, Turn.CLOCKWISE), FaceTurn(Face.D, Turn.COUNTERCLOCKWISE))
        val opposite =
            Assess.check(
                CandidateEnumerator.of(expected, TutorFixtures.CONFIG),
                partial(hidden, setOf(Face.U, Face.D)),
                setOf(Face.U, Face.D),
            )
        assertEquals(Certainty.LIKELY, opposite.certainty)
        assertTrue(opposite.matchesExpected)
    }

    /**
     * Every face-turn sequence of length <= 3 from seeded states whose plan faces it leaves unchanged.
     * Within the candidate model (length <= maxDepth) such a sequence is itself a consistent candidate
     * beside `[]`, so it never verifies. A longer one can alias a match: the residual risk the spec's
     * Change Log records. Every alias found must alias a match (`[]`), never name a wrong mismatch.
     */
    @Test fun `sequences that leave the observed faces unchanged`() {
        val turns = TutorFixtures.CONFIG.moves.filterIsInstance<FaceTurn>()
        val random = Random(10603)
        var sequences = 0
        var preserving = 0
        var aliases = 0
        repeat(10) {
            val state = CubeState.SOLVED.apply(List(20) { turns.random(random) })
            val enumerator = CandidateEnumerator.of(state, TutorFixtures.CONFIG)
            for (size in 1..2) {
                val mustCover =
                    Face.entries
                        .shuffled(random)
                        .take(size)
                        .toSet()
                val plan = QuickCheck.faces(mustCover)
                val reference = partial(state, plan)

                fun inspect(
                    actual: CubeState,
                    path: List<Move>,
                ) {
                    sequences++
                    if (actual.cubies != state.cubies && partial(actual, plan).faces == reference.faces) {
                        preserving++
                        val check = Assess.check(enumerator, partial(actual, plan), mustCover)
                        if (check.certainty == Certainty.VERIFIED) {
                            aliases++
                            println("Residual alias: mustCover=$mustCover plan=$plan path=$path")
                            assertEquals(3, path.size, "an in-model sequence verified: $path")
                            assertTrue(check.matchesExpected, "an alias named a wrong mismatch: $path")
                        }
                    }
                    if (path.size < 3) turns.forEach { inspect(actual.apply(it), path + it) }
                }
                inspect(state, emptyList())
            }
        }
        println("Observed-face-preserving sequences: $preserving of $sequences; residual aliases of a match: $aliases")
        assertEquals(20 * (1 + turns.size + turns.size * turns.size + turns.size * turns.size * turns.size), sequences)
        assertTrue(preserving > 0)
    }
}
