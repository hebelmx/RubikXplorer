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
        assertTrue(verifiedMatches > 0)
        assertTrue(verifiedMismatches > 0)
        Certainty.entries.forEach { assertTrue(counts.getOrDefault(it, 0) > 0, "$it missing") }
    }
}
