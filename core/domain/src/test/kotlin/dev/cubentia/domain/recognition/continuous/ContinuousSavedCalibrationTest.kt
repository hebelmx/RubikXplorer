package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 10.37 E9 (parity id I1037): the saved-calibration fallback anchor of [ContinuousFaceletMapper], one test per row of
 * the spec's I/O matrix, on synthetic pastel and off-palette cubes ([SyntheticCubeStream]).
 */
class ContinuousSavedCalibrationTest {
    private val scenarios = SyntheticCubeStream.buildScenarios()

    /** The S2 path (all six sides, no regrip) on [palette], without the AWB drift. */
    private fun cube(palette: SyntheticCubeStream.Palette) = scenarios[1].copy(palette = palette, awbDrift = false)

    private val pastel = cube(SyntheticCubeStream.Palette.PASTEL)
    private val offPalette = cube(SyntheticCubeStream.Palette.OFF_PALETTE)

    /** One frame's outcome: the snapshot and the mapper's calibration state after it. */
    private class Step(
        val frameIndex: Int,
        val snapshot: ContinuousScanSnapshot,
        val byCalibration: Boolean,
        val calibrationUnavailable: Boolean,
        val wrong: Int,
    )

    private fun run(
        cfg: SyntheticCubeStream.ScenarioConfig,
        calibration: CalibrationCenters?,
        mapper: ContinuousFaceletMapper = ContinuousFaceletMapper(),
        torch: (Int) -> Boolean = { false },
    ): List<Step> {
        val truth = SyntheticCubeStream.stateToNet(cfg.state)
        return SyntheticCubeStream.generateSequence(cfg).map { fr ->
            val snap = mapper.processFrame(fr.frameIndex, fr.detections, calibration, torch(fr.frameIndex))
            Step(fr.frameIndex, snap, mapper.anchoredByCalibration, mapper.calibrationUnavailable, wrong(snap, truth))
        }
    }

    private fun wrong(
        snap: ContinuousScanSnapshot,
        truth: Map<Face, List<Color>>,
    ): Int = Face.entries.sumOf { f -> (0 until 9).count { i -> snap.net.getValue(f)[i].let { it != null && it != truth.getValue(f)[i] } } }

    /** The same cube's own calibration under the same light: the six standard centres a full calibration-less scan exports. */
    private fun ownCalibration(cfg: SyntheticCubeStream.ScenarioConfig): CalibrationCenters {
        val mapper = ContinuousFaceletMapper()
        SyntheticCubeStream.generateSequence(cfg.copy(twistAtFrame = null)).forEach { mapper.processFrame(it.frameIndex, it.detections) }
        return checkNotNull(mapper.exportHandoff().calibration) { "${cfg.name} must complete without a calibration" }
    }

    private fun firstAnchored(steps: List<Step>): Int? = steps.firstOrNull { it.snapshot.globalAnchored }?.frameIndex

    private fun assertComplete(
        cfg: SyntheticCubeStream.ScenarioConfig,
        steps: List<Step>,
    ) {
        val last = assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, steps.last().snapshot)
        assertEquals(cfg.state.toFacelets(), last.state.toFacelets())
        assertEquals(0, steps.maxOf { it.wrong }, "a wrong sticker was committed")
    }

    /** Frame-by-frame equality of the compared snapshot fields with a calibration-less run. */
    private fun assertSameAsWithout(
        cfg: SyntheticCubeStream.ScenarioConfig,
        steps: List<Step>,
    ) {
        val plain = run(cfg, null)
        assertEquals(plain.map { it.snapshot }, steps.map { it.snapshot })
        assertFalse(steps.any { it.byCalibration })
    }

    @Test
    fun `a matching calibration anchors a pastel or off-palette cube before the sixth centre and never mis-anchors`() {
        val cubes =
            listOf(
                pastel,
                offPalette,
                scenarios[1],
                scenarios[0].copy(palette = SyntheticCubeStream.Palette.OFF_PALETTE, regripRange = null, regripReturnEuler = null),
                scenarios[5].copy(palette = SyntheticCubeStream.Palette.OFF_PALETTE),
            )
        for (cfg in cubes) {
            val without = run(cfg, null)
            val with = run(cfg, ownCalibration(cfg))
            val plainFirst = checkNotNull(firstAnchored(without))
            val calFirst = checkNotNull(firstAnchored(with)) { "${cfg.name}: never anchored" }
            assertTrue(calFirst < plainFirst, "${cfg.name}: anchored at $calFirst, not before $plainFirst")
            val first = with.first { it.snapshot.globalAnchored }
            assertTrue(first.byCalibration, "${cfg.name}: the early anchor must be the calibration's")
            assertTrue(first.snapshot.mappedFacesCount < Face.entries.size)
            assertComplete(cfg, with)
        }
    }

    @Test
    fun `when the built-in anchor passes the calibration is not consulted`() {
        // A stickered cube anchors on REF_CENTER_LAB; even another cube's calibration changes nothing.
        val stickered = cube(SyntheticCubeStream.Palette.STICKERED)
        assertSameAsWithout(stickered, run(stickered, ownCalibration(pastel)))
        assertSameAsWithout(stickered, run(stickered, ownCalibration(stickered)))
    }

    @Test
    fun `no calibration or an invalid one is today's behaviour`() {
        val own = ownCalibration(offPalette)
        val fiveColours = CalibrationCenters(own.centers - Color.BLUE)
        val invalidLab = CalibrationCenters(own.centers + (Color.RED to Lab(Double.NaN, 0.0, 0.0)))
        for (cal in listOf(null, fiveColours, invalidLab)) assertSameAsWithout(offPalette, run(offPalette, cal))
    }

    @Test
    fun `another cube's or another light's calibration makes no anchor`() {
        val pastelCal = ownCalibration(pastel)
        val offCal = ownCalibration(offPalette)
        val stickeredCal = ownCalibration(cube(SyntheticCubeStream.Palette.STICKERED))
        val cases =
            listOf(
                offPalette to pastelCal,
                pastel to offCal,
                pastel to stickeredCal,
                offPalette to stickeredCal,
            ) +
                listOf(0.6, 0.75, 1.3).map { k ->
                    offPalette to CalibrationCenters(offCal.centers.mapValues { (_, l) -> l.copy(l = (l.l * k).coerceIn(0.0, 100.0)) })
                }
        for ((cfg, cal) in cases) assertSameAsWithout(cfg, run(cfg, cal))
    }

    @Test
    fun `a calibration anchors only when its torch tag equals the torch lit`() {
        val own = ownCalibration(offPalette)
        val taggedOn = own.copy(torchOn = true)
        val taggedOff = own.copy(torchOn = false)
        // Tagged on, torch off; tagged off, torch on; untagged (torch-off) with the torch on.
        assertSameAsWithout(offPalette, run(offPalette, taggedOn))
        val lit = { _: Int -> true }
        for (cal in listOf(taggedOff, own)) {
            val steps = run(offPalette, cal, torch = lit)
            assertEquals(run(offPalette, null, torch = lit).map { it.snapshot }, steps.map { it.snapshot })
            assertFalse(steps.any { it.byCalibration })
        }
        // Equal tags anchor: tagged on with the torch on, tagged off (or untagged) with it off.
        assertTrue(run(offPalette, taggedOn, torch = lit).any { it.byCalibration })
        assertTrue(run(offPalette, taggedOff).any { it.byCalibration })
    }

    @Test
    fun `a later centre that fails the match revokes the calibration anchor`() {
        val own = ownCalibration(offPalette)
        val good = run(offPalette, own)
        val anchoredAt = good.first { it.byCalibration }
        val seen = centreColours(anchoredAt.snapshot)
        // The first colour whose centre joins the net while the calibration anchor holds and fewer than six are mapped.
        val later =
            good
                .filter { it.byCalibration && it.frameIndex > anchoredAt.frameIndex }
                .firstNotNullOf { step -> (centreColours(step.snapshot) - seen).firstOrNull() }
        val corrupted = CalibrationCenters(own.centers + (later to Lab(50.0, 0.0, 0.0)))
        val steps = run(offPalette, corrupted)
        val firstByCal = steps.indexOfFirst { it.byCalibration }
        assertTrue(firstByCal >= 0, "the first centres still match")
        // After the last calibration anchor the map stays unanchored and empty until the six-centre self-calibration.
        val revokedAt = steps.indexOfLast { it.byCalibration } + 1
        val legitimate = steps.drop(revokedAt).indexOfFirst { it.snapshot.globalAnchored }
        assertTrue(legitimate > 0, "the calibration anchor must be revoked before another anchor")
        val unanchored = steps.subList(revokedAt, revokedAt + legitimate)
        assertTrue(unanchored.all { !it.snapshot.globalAnchored && it.snapshot.committedCount == 0 })
        assertTrue(unanchored.first().snapshot.mappedFacesCount < Face.entries.size, "revoked before the sixth centre")
        val anchor = steps[revokedAt + legitimate]
        assertFalse(anchor.byCalibration)
        assertEquals(Face.entries.size, anchor.snapshot.mappedFacesCount)
        assertTrue(steps.count { it.byCalibration } < good.count { it.byCalibration })
        // Self-calibration completes as without a calibration.
        assertComplete(offPalette, steps)
    }

    private fun centreColours(snap: ContinuousScanSnapshot): Set<Color> =
        Face.entries.mapNotNullTo(LinkedHashSet()) {
            snap.net.getValue(it)[4]
        }

    @Test
    fun `a torch toggle before any centre evidence leaves the calibration eligible under the new state`() {
        val cal = ownCalibration(offPalette).copy(torchOn = true)
        val mapper = ContinuousFaceletMapper()
        // No cube in view yet: off, on, off, on.
        listOf(false, true, false, true).forEachIndexed { i, lit -> mapper.processFrame(-10 + i, emptyList(), cal, lit) }
        assertFalse(mapper.calibrationUnavailable)
        val steps = run(offPalette, cal, mapper, torch = { true })
        assertTrue(steps.any { it.byCalibration })
        assertComplete(offPalette, steps)
    }

    @Test
    fun `a torch toggle after centre evidence revokes the anchor and turns the calibration off until Reset`() {
        val cal = ownCalibration(offPalette)
        val anchoredAt = run(offPalette, cal).first { it.byCalibration }.frameIndex
        val toggle = anchoredAt + 3
        val mapper = ContinuousFaceletMapper()
        // On for one frame, then back off.
        val steps = run(offPalette, cal, mapper, torch = { it == toggle })
        val atToggle = steps.first { it.frameIndex == toggle }
        assertFalse(atToggle.snapshot.globalAnchored)
        assertTrue(atToggle.calibrationUnavailable)
        assertTrue(steps.filter { it.frameIndex >= toggle }.none { it.byCalibration })
        assertTrue(steps.filter { it.frameIndex >= toggle && it.snapshot.globalAnchored }.all { it.snapshot.mappedFacesCount == 6 })
        assertComplete(offPalette, steps)

        mapper.reset()
        assertFalse(mapper.calibrationUnavailable)
        val again = run(offPalette, cal, mapper)
        assertEquals(anchoredAt, again.first { it.byCalibration }.frameIndex)
    }

    @Test
    fun `an ambiguous six-centre solve drops the calibration anchor and bars the calibration`() {
        // Blue and Orange are Green and Red at half brightness: equal in the shading-invariant six-centre solve.
        val rgb = SyntheticCubeStream.paletteRgb(SyntheticCubeStream.Palette.SIX_AMBIGUOUS)
        val cal =
            CalibrationCenters(
                rgb.mapValues { (_, p) ->
                    ContinuousColorModel.rgbToLab(RgbColor(p.r * 0.95, p.g * 0.95, p.b * 0.95))
                },
            )
        val cubes =
            listOf(
                cube(SyntheticCubeStream.Palette.SIX_AMBIGUOUS),
                scenarios[0].copy(palette = SyntheticCubeStream.Palette.SIX_AMBIGUOUS, regripRange = null, regripReturnEuler = null),
            )
        for (cfg in cubes) {
            val steps = run(cfg, cal)
            assertTrue(steps.any { it.byCalibration && it.snapshot.mappedFacesCount < 6 }, "${cfg.name}: no early anchor")
            val six = steps.indexOfFirst { it.snapshot.mappedFacesCount == 6 }
            assertTrue(six >= 0)
            val after = steps.drop(six)
            assertTrue(after.none { it.snapshot.globalAnchored }, "${cfg.name}: anchored after an ambiguous six-centre solve")
            assertTrue(after.all { it.calibrationUnavailable })
            assertEquals(0, steps.maxOf { it.wrong })
        }
    }

    @Test
    fun `a decisive six-centre solve takes over and a later torch toggle keeps it`() {
        val cal = ownCalibration(offPalette)
        val plain = run(offPalette, cal)
        val six = plain.first { it.snapshot.globalAnchored && !it.byCalibration && it.snapshot.mappedFacesCount == 6 }.frameIndex
        val toggle = six + 2
        val steps = run(offPalette, cal, torch = { it >= toggle })
        val afterToggle = steps.filter { it.frameIndex >= toggle }
        assertTrue(afterToggle.all { it.snapshot.globalAnchored && !it.byCalibration })
        assertTrue(afterToggle.first().calibrationUnavailable)
        assertComplete(offPalette, steps)
    }

    @Test
    fun `an unreadable or off-rule centre makes no calibration anchor`() {
        val saved = ownCalibration(offPalette)
        // U, F and R of the home orientation: White, Green and Red.
        val exact =
            mapOf(
                Face.U to saved.centers.getValue(Color.WHITE),
                Face.F to saved.centers.getValue(Color.GREEN),
                Face.R to saved.centers.getValue(Color.RED),
            )
        val rot = checkNotNull(SavedCalibrationAnchor.orientation(exact, saved, torchLit = false))
        for ((face, lab) in exact) assertEquals(lab, saved.centers.getValue(Color.home(ContinuousCubeGeometry.rotateFace(face, rot))))
        assertNull(SavedCalibrationAnchor.orientation(exact + (Face.B to null), saved, torchLit = false))
        assertNull(SavedCalibrationAnchor.orientation(exact + (Face.B to Lab(Double.NaN, 0.0, 0.0)), saved, torchLit = false))
        assertNull(SavedCalibrationAnchor.orientation(emptyMap(), saved, torchLit = false))
        assertNull(SavedCalibrationAnchor.orientation(exact, saved, torchLit = true))
        assertNull(SavedCalibrationAnchor.orientation(exact, null, torchLit = false))
        // A centre just past MAX_CENTRE_DELTA_E (Red 12.3) of its colour fails, though the orientation is still clear.
        val red = saved.centers.getValue(Color.RED)
        assertNull(SavedCalibrationAnchor.orientation(exact + (Face.R to red.copy(l = red.l + 12.5)), saved, torchLit = false))
    }

    @Test
    fun `after a calibration anchor every twist the calibration-less run detects is detected with no more wrong stickers`() {
        var detectedWithout = 0
        for (palette in listOf(SyntheticCubeStream.Palette.PASTEL, SyntheticCubeStream.Palette.OFF_PALETTE)) {
            val clean = cube(palette)
            val cal = ownCalibration(clean)
            for (at in listOf(45, 60)) {
                for (move in listOf("U", "R", "F", "L", "D", "B")) {
                    val cfg = clean.copy(twistAtFrame = at, twistMove = move)
                    val without = run(cfg, null)
                    val with = run(cfg, cal)
                    val name = "$palette $move at $at"
                    assertTrue(with.any { it.byCalibration && it.frameIndex < at }, "$name: no calibration anchor before the twist")
                    val twistWithout = without.firstOrNull { it.snapshot.twistDetected }?.frameIndex
                    val twistWith = with.firstOrNull { it.snapshot.twistDetected }?.frameIndex
                    if (twistWithout != null) {
                        detectedWithout++
                        assertNotNull(twistWith, "$name: missed a twist detected at $twistWithout without a calibration")
                        assertTrue(twistWith!! <= twistWithout, "$name: detected at $twistWith, later than $twistWithout")
                    }
                    assertTrue(with.maxOf { it.wrong } <= without.maxOf { it.wrong }, "$name: more wrong stickers than without")
                }
            }
        }
        assertTrue(detectedWithout >= 12, "the twist cases must exercise detection: $detectedWithout")
    }

    @Test
    fun `a torch change with centre evidence but no anchor yet turns the calibration off`() {
        val cal = ownCalibration(offPalette)
        val anchoredAt = run(offPalette, cal).first { it.byCalibration }.frameIndex
        // The cube is in view from frame 1; the toggle comes before the calibration would anchor.
        val toggle = 5
        assertTrue(toggle < anchoredAt)
        val steps = run(offPalette, cal, torch = { it == toggle })
        assertFalse(steps.first { it.frameIndex == toggle - 1 }.calibrationUnavailable)
        assertTrue(steps.first { it.frameIndex == toggle }.calibrationUnavailable)
        assertTrue(steps.none { it.byCalibration })
        assertComplete(offPalette, steps)
    }

    @Test
    fun `a calibration barred by an ambiguous six-centre solve is usable again after reset`() {
        val cfg = cube(SyntheticCubeStream.Palette.SIX_AMBIGUOUS)
        val cal = ambiguousCalibration()
        val mapper = ContinuousFaceletMapper()
        val first = run(cfg, cal, mapper)
        assertTrue(first.last().calibrationUnavailable)
        mapper.reset()
        assertFalse(mapper.calibrationUnavailable)
        val again = run(cfg, cal, mapper)
        assertEquals(first.first { it.byCalibration }.frameIndex, again.first { it.byCalibration }.frameIndex)
    }

    private fun ambiguousCalibration(): CalibrationCenters {
        val rgb = SyntheticCubeStream.paletteRgb(SyntheticCubeStream.Palette.SIX_AMBIGUOUS)
        return CalibrationCenters(rgb.mapValues { (_, p) -> ContinuousColorModel.rgbToLab(RgbColor(p.r * 0.95, p.g * 0.95, p.b * 0.95)) })
    }

    /** Fragment bookkeeping that must not depend on how the master is anchored: per frame, sides, pending sides, twist. */
    private fun fragments(steps: List<Step>) =
        steps.map { Triple(it.snapshot.mappedFacesCount, it.snapshot.pendingFragmentFaces, it.snapshot.twistDetected) }

    @Test
    fun `a calibration anchor leaves every regrip promotion and merge as in the calibration-less run`() {
        // tryMergePending's promotion rules treat a calibration-made anchor as unanchored. A parity guard: no synthetic regrip
        // found so far makes the rules diverge even without that treatment.
        var anchoredRegrips = 0
        for (palette in listOf(SyntheticCubeStream.Palette.PASTEL, SyntheticCubeStream.Palette.OFF_PALETTE)) {
            for (template in listOf(scenarios[0], scenarios[3])) {
                for (seed in listOf(template.seed, 1L, 2L, 3L)) {
                    val cfg = template.copy(palette = palette, seed = seed)
                    val cal = ownCalibration(cfg)
                    val without = run(cfg, null)
                    val with = run(cfg, cal)
                    val regrip = checkNotNull(cfg.regripRange)
                    if (with.any { it.byCalibration && it.frameIndex > regrip.last }) anchoredRegrips++
                    assertEquals(fragments(without), fragments(with), "${cfg.name} $palette seed $seed")
                }
            }
        }
        assertTrue(anchoredRegrips > 0, "no case had a calibration anchor around a regrip")
    }

    @Test
    fun `the orientation needs an average centre dE below MAX_MEAN_DELTA_E`() {
        // White exact; Green and Blue moved along L inside their MAX_CENTRE_DELTA_E (40.4, 31.3) and still CONFIDENT.
        fun centres(greenShift: Double) =
            mapOf(
                Face.U to SAVED.getValue(Color.WHITE),
                Face.F to SAVED.getValue(Color.GREEN).let { it.copy(l = it.l + greenShift) },
                Face.B to SAVED.getValue(Color.BLUE).let { it.copy(l = it.l - 30.0) },
            )
        assertNotNull(SavedCalibrationAnchor.orientation(centres(29.0), CalibrationCenters(SAVED), torchLit = false))
        assertNull(SavedCalibrationAnchor.orientation(centres(31.0), CalibrationCenters(SAVED), torchLit = false))
    }

    @Test
    fun `the orientation needs a margin of MIN_MARGIN over the second-best one`() {
        // One centre fits four orientations equally (margin 0); a second adjacent one fixes the orientation.
        val white = mapOf(Face.U to SAVED.getValue(Color.WHITE))
        assertNull(SavedCalibrationAnchor.orientation(white, CalibrationCenters(SAVED), torchLit = false))
        val whiteGreen = white + (Face.F to SAVED.getValue(Color.GREEN))
        assertNotNull(SavedCalibrationAnchor.orientation(whiteGreen, CalibrationCenters(SAVED), torchLit = false))
    }

    @Test
    fun `the orientation needs every centre to read CONFIDENT as its colour`() {
        val centres =
            mapOf(Face.U to SAVED.getValue(Color.WHITE), Face.F to SAVED.getValue(Color.GREEN), Face.R to SAVED.getValue(Color.RED))
        assertNotNull(SavedCalibrationAnchor.orientation(centres, CalibrationCenters(SAVED), torchLit = false))
        // Orange saved 7 dE from Red: the exact Red centre reads Red with a margin below 10, UNCERTAIN.
        val closeOrange = SAVED + (Color.ORANGE to Lab(50.0, 66.0, 48.0))
        assertNull(SavedCalibrationAnchor.orientation(centres, CalibrationCenters(closeOrange), torchLit = false))
    }

    private companion object {
        /** Six saved centres far apart (Red to Orange, the closest pair, about dE 40). */
        val SAVED: Map<Color, Lab> =
            mapOf(
                Color.WHITE to Lab(95.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 85.0),
                Color.GREEN to Lab(55.0, -70.0, 50.0),
                Color.BLUE to Lab(35.0, 20.0, -70.0),
                Color.RED to Lab(45.0, 70.0, 45.0),
                Color.ORANGE to Lab(65.0, 45.0, 70.0),
            )
    }
}
