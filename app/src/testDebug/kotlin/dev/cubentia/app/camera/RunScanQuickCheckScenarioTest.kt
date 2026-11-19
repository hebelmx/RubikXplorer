package dev.cubentia.app.camera

import dev.cubentia.app.session.shippedTutorConfig
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.tutor.Assess
import dev.cubentia.domain.tutor.CandidateEnumerator
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.Coverage
import dev.cubentia.domain.tutor.QuickCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * GH #172: 2-face Quick Checks classified against the run's start-scan colors (no saved calibration). The start scan's
 * centers come from [SelfCalibration.from] over a noisy palette; the Quick Check samples of the actual cube's plan faces
 * go through [GuidedScanFlow.quickCheckOf] with `runScan = true`, then [Assess.check]. Generator as in the domain's
 * `QuickCheckScenarioTest` (seeded, perturbation by `scenario % 6`).
 */
class RunScanQuickCheckScenarioTest {
    private val palette =
        mapOf(
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
        )
    private val turns = shippedTutorConfig.moves.filterIsInstance<FaceTurn>()

    /** A palette color with seeded noise of at most [MAX_NOISE] ΔE76, shifted by [light]. */
    private fun sticker(
        color: Color,
        random: Random?,
        light: (Lab) -> Lab = { it },
    ): Lab {
        val base = palette.getValue(color)
        if (random == null) return light(base)
        val radius = random.nextDouble() * MAX_NOISE
        val theta = random.nextDouble() * Math.PI
        val phi = random.nextDouble() * 2 * Math.PI
        return light(
            Lab(
                base.l + radius * cos(theta),
                base.a + radius * sin(theta) * cos(phi),
                base.b + radius * sin(theta) * sin(phi),
            ),
        )
    }

    private fun netColors(
        state: CubeState,
        face: Face,
    ): List<Color> =
        state
            .reoriented(Orientation.HOME)
            .toFacelets()
            .colors
            .subList(face.ordinal * 9, face.ordinal * 9 + 9)

    /** The re-prompt pose shows the face as the net draws it, so pixel order is net order at 0°. */
    private fun quickSample(
        state: CubeState,
        face: Face,
        random: Random?,
        light: (Lab) -> Lab = { it },
    ): FaceSample {
        val step = GuidedScanPlan.reprompt(face)
        return FaceSample(
            face = face,
            orientationDegrees = 0,
            stickers = netColors(state, face).map { sticker(it, random, light) },
            quality = CaptureQuality.ACCEPTABLE,
            centerColor = step.centerColor,
            upNeighborColor = step.upNeighborColor,
            centerPlain = true,
        )
    }

    /** The Guided 6-face start scan's colors: [SelfCalibration.from] with no saved calibration. */
    private fun runScanColors(
        start: CubeState,
        random: Random,
    ): CalibrationCenters {
        val samples =
            Face.entries.map { face ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = netColors(start, face).map { sticker(it, random) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.home(face),
                    centerPlain = true,
                )
            }
        return (SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration
    }

    private class Scenario(
        val start: CubeState,
        val expected: CubeState,
        val actual: CubeState,
        val mustCover: Set<Face>,
        val plan: Set<Face>,
    )

    private fun scenarios(seed: Int): Sequence<Pair<Int, Scenario>> {
        val random = Random(seed)
        return generateSequence(0) { it + 1 }.take(SCENARIOS).mapNotNull { scenario ->
            val start =
                CubeState.SOLVED
                    .apply(List(20) { turns.random(random) })
                    .apply(CubeRotation(Axis.entries.random(random), Turn.entries.random(random)))
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
            val mustCover = Coverage.touched(start, confirmed)
            val plan = QuickCheck.faces(mustCover)
            // Only a subset is a run-scan Quick Check; 6 faces are the full scan.
            if (plan.size == Face.entries.size) null else scenario to Scenario(start, expected, start.apply(actualMoves), mustCover, plan)
        }
    }

    @Test fun `run-scan quick checks under the start scan's light never falsely verify`() {
        val random = Random(172)
        var count = 0
        var falseVerified = 0
        var matchingPlans = 0
        var verifiedMatches = 0
        scenarios(172).forEach { (scenario, s) ->
            count++
            val reference = runScanColors(s.start, random)
            val planFaces = Face.entries.filter { it in s.plan }
            // Precondition: a noise-free synthesis round-trips each face exactly.
            val exact = GuidedScanFlow.quickCheckOf(planFaces.map { quickSample(s.actual, it, null) }, reference, runScan = true)
            assertEquals("scenario $scenario", planFaces.associateWith { netColors(s.actual, it) }, exact.faces)
            val partial = GuidedScanFlow.quickCheckOf(planFaces.map { quickSample(s.actual, it, random) }, reference, runScan = true)
            val check = Assess.check(CandidateEnumerator.of(s.expected, shippedTutorConfig), partial, s.mustCover)
            if (check.certainty == Certainty.VERIFIED && check.consistent
                    .single()
                    .state.cubies != s.actual.cubies
            ) {
                falseVerified++
                println("False verification: scenario=$scenario plan=${s.plan} candidate=${check.consistent.single().moves}")
            }
            if (s.actual.cubies == s.expected.cubies) {
                matchingPlans++
                if (check.certainty == Certainty.VERIFIED) verifiedMatches++
            }
        }
        println(
            "Run-scan Quick Check: $count scenarios, $falseVerified false verified; matching plans verified $verifiedMatches/$matchingPlans",
        )
        assertTrue(count >= MIN_SCENARIOS)
        assertEquals(0, falseVerified)
        assertTrue(verifiedMatches > 0)
    }

    @Test fun `a lighting change after the start scan makes every captured face doubtful`() {
        val random = Random(1720)
        val dimmer: (Lab) -> Lab = { Lab(it.l - 15.0, it.a, it.b + 20.0) }
        var count = 0
        scenarios(1720).forEach { (scenario, s) ->
            count++
            val reference = runScanColors(s.start, random)
            val planFaces = Face.entries.filter { it in s.plan }
            val samples = planFaces.map { quickSample(s.actual, it, random, dimmer) }
            val partial = GuidedScanFlow.quickCheckOf(samples, reference, runScan = true)
            assertTrue("scenario $scenario", partial.faces.isEmpty())
            assertEquals("scenario $scenario", planFaces, GuidedScanFlow.quickCheckRetakes(samples, reference, runScan = true))
            val check = Assess.check(CandidateEnumerator.of(s.expected, shippedTutorConfig), partial, s.mustCover)
            assertNotEquals("scenario $scenario", Certainty.VERIFIED, check.certainty)
        }
        assertTrue(count >= MIN_SCENARIOS)
    }

    @Test fun `a light shift just under the guard never falsely verifies`() {
        val random = Random(1722)
        // ΔE76 ≈ 8.6 on every sticker: under the 10 ΔE guard before noise, so many checks pass the guard.
        val shift: (Lab) -> Lab = { Lab(it.l - 5.0, it.a, it.b + 7.0) }
        var count = 0
        var guarded = 0
        var falseVerified = 0
        scenarios(1722).forEach { (scenario, s) ->
            count++
            val reference = runScanColors(s.start, random)
            val planFaces = Face.entries.filter { it in s.plan }
            val partial = GuidedScanFlow.quickCheckOf(planFaces.map { quickSample(s.actual, it, random, shift) }, reference, runScan = true)
            if (partial.faces.isNotEmpty()) guarded++
            val check = Assess.check(CandidateEnumerator.of(s.expected, shippedTutorConfig), partial, s.mustCover)
            if (check.certainty == Certainty.VERIFIED && check.consistent
                    .single()
                    .state.cubies != s.actual.cubies
            ) {
                falseVerified++
                println(
                    "False verification (sub-threshold light): scenario=$scenario plan=${s.plan} candidate=${check.consistent.single().moves}",
                )
            }
        }
        println("Run-scan Quick Check, sub-threshold light: $count scenarios, $guarded passed the guard, $falseVerified false verified")
        assertTrue(count >= MIN_SCENARIOS)
        assertTrue(guarded > 0)
        assertEquals(0, falseVerified)
    }

    @Test fun `a logo-only capture has no light probe and is doubtful`() {
        val random = Random(17)
        val reference = runScanColors(CubeState.SOLVED, random)
        val logo = quickSample(CubeState.SOLVED, Face.U, random).copy(centerPlain = false)
        assertTrue(GuidedScanFlow.quickCheckOf(listOf(logo), reference, runScan = true).faces.isEmpty())
        assertEquals(listOf(Face.U), GuidedScanFlow.quickCheckRetakes(listOf(logo), reference, runScan = true))
        // Without the run-scan flag (a saved calibration), the same capture is judged as today.
        assertEquals(setOf(Face.U), GuidedScanFlow.quickCheckOf(listOf(logo), reference).faces.keys)
        // A plain center beside it is the probe: unchanged light keeps both.
        val plain = quickSample(CubeState.SOLVED, Face.R, random)
        assertEquals(setOf(Face.U, Face.R), GuidedScanFlow.quickCheckOf(listOf(logo, plain), reference, runScan = true).faces.keys)
    }

    @Test fun `a start scan returns its centers only when uncalibrated and self-calibrating`() {
        val random = Random(1721)
        val samples =
            Face.entries.map { face ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = netColors(CubeState.SOLVED, face).map { sticker(it, random) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.home(face),
                    centerPlain = true,
                )
            }
        val measured = GuidedScanFlow.runScanColorsOf(samples, null)
        assertEquals((SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration, measured)
        // A saved calibration wins: nothing is returned for the run.
        assertEquals(null, GuidedScanFlow.runScanColorsOf(samples, measured))
        // Two unusable centers: NeedsCalibration, so no run-scan colors and Quick Checks stay at 6 faces.
        val unusable = samples.mapIndexed { i, s -> if (i < 2) s.copy(quality = CaptureQuality.BLURRED) else s }
        assertEquals(null, GuidedScanFlow.runScanColorsOf(unusable, null))
    }

    private companion object {
        const val MAX_NOISE = 3.0
        const val SCENARIOS = 2600
        const val MIN_SCENARIOS = 2000
    }
}
