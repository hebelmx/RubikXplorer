package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.RecognitionFixtures
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanGap
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.SuspectRanking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuousHandoffTest {
    private val scrambled = SyntheticCubeStream.scramble("R U R' F' L2 D B' U2 R' F L D'")
    private val scrambledFacelets = scrambled.toFacelets()
    private val scrambledNet = SyntheticCubeStream.stateToNet(scrambled)

    private fun runScenario6Export(): ContinuousScanExport.Complete {
        val cfg = SyntheticCubeStream.buildScenarios()[5] // S6: Y/O/B start without White
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val mapper = ContinuousFaceletMapper()
        for (fr in frames) {
            mapper.processFrame(fr.frameIndex, fr.detections)
        }
        val exported = mapper.export()
        return assertInstanceOf(ContinuousScanExport.Complete::class.java, exported)
    }

    @Test
    fun `non-HOME YOB scan canonicalizes to Orientation HOME and ScanReview preserves all 54 stickers`() {
        val exported = runScenario6Export()
        assertEquals(Orientation.HOME, exported.state.orientation)
        assertEquals(scrambledFacelets, exported.facelets)
        assertTrue(exported.handoff.isComplete)
        assertNotNull(exported.handoff.calibration)

        val review = ScanReview.from(exported.handoff)
        assertTrue(review.blanks.isEmpty(), "Expected 0 blanks, got ${review.blanks}")
        assertTrue(review.problems.isEmpty(), "Expected 0 problems, got ${review.problems}")
        assertEquals(scrambledFacelets, review.accepted?.toFacelets())
        val evidence = requireNotNull(review.evidence)
        assertEquals(6, evidence.centers.size)
        assertEquals(54, evidence.labs.count { it != null })
    }

    @Test
    fun `Story 10-16 sequential retakes from continuous ScanReview work with standard CIELAB samples and no saved calibration`() {
        val exported = runScenario6Export()
        val review = ScanReview.from(exported.handoff)
        val cal = requireNotNull(exported.handoff.calibration)
        // Calibration exported for Review/Guided handoff must be standard D65 CIELAB (not L2-normalized ~51 L*)
        assertTrue(
            cal.centers.getValue(Color.WHITE).l > 70.0,
            "Exported WHITE calibration center must be standard D65 CIELAB, got ${cal.centers.getValue(Color.WHITE)}",
        )

        // Realistic standard D65 CIELAB Guided samples (as FrameReader.rgbToLab produces)
        val standardPalette =
            mapOf(
                Color.WHITE to ContinuousColorModel.rgbToLab(RgbColor(225.0, 227.0, 229.0)),
                Color.YELLOW to ContinuousColorModel.rgbToLab(RgbColor(224.0, 202.0, 30.0)),
                Color.GREEN to ContinuousColorModel.rgbToLab(RgbColor(32.0, 160.0, 68.0)),
                Color.BLUE to ContinuousColorModel.rgbToLab(RgbColor(26.0, 82.0, 196.0)),
                Color.RED to ContinuousColorModel.rgbToLab(RgbColor(194.0, 36.0, 40.0)),
                Color.ORANGE to ContinuousColorModel.rgbToLab(RgbColor(228.0, 112.0, 26.0)),
            )

        // 1st retake: Learner edits one sticker on U, then retakes F (rotated 90 deg, saved = null, empty Guided samples)
        val edited = review.corrected(mapOf(0 to Color.YELLOW))
        val fColors = scrambledNet.getValue(Face.F)
        val fSample =
            FaceSample(
                face = Face.F,
                orientationDegrees = 90,
                stickers = fColors.map { standardPalette.getValue(it) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = Color.GREEN,
            )

        val afterFirstRetake = ScanReview.retaken(edited, listOf(fSample), samples = emptyList(), saved = null)
        assertTrue(afterFirstRetake.blanks.isEmpty(), "First retake must not fail with NeedsCalibration")
        assertEquals(Color.YELLOW, afterFirstRetake.stickers[0], "Kept face edit must be preserved")
        assertEquals(fColors, afterFirstRetake.stickers.subList(18, 27), "First retaken face must orient cleanly")
        assertNotNull(afterFirstRetake.evidence)

        // 2nd consecutive retake: samples now holds [fSample] (size 1, non-empty!) and saved is still null
        val mergedAfterFirst = ScanReview.mergedSamples(emptyList(), listOf(fSample))
        assertEquals(1, mergedAfterFirst.size)
        val rColors = scrambledNet.getValue(Face.R)
        val rSample =
            FaceSample(
                face = Face.R,
                orientationDegrees = 180,
                stickers = rColors.map { standardPalette.getValue(it) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = Color.RED,
            )
        val afterSecondRetake = ScanReview.retaken(afterFirstRetake, listOf(rSample), samples = mergedAfterFirst, saved = null)
        assertTrue(afterSecondRetake.blanks.isEmpty(), "Second consecutive retake must reuse evidence centers")
        assertEquals(Color.YELLOW, afterSecondRetake.stickers[0])
        assertEquals(rColors, afterSecondRetake.stickers.subList(9, 18), "Second retaken face must orient cleanly")
    }

    @Test
    fun `issue 323 SuspectRanking ranks perturbed stickers from continuous ScanReview evidence`() {
        val exported = runScenario6Export()
        val cal = requireNotNull(exported.handoff.calibration)
        val baseReview = ScanReview.from(exported.handoff)

        // Perturb one non-center RED sticker to ORANGE with a Lab right between RED and ORANGE
        val redIdx = (0 until 54).first { it % 9 != 4 && baseReview.stickers[it] == Color.RED }
        val redLab = cal.centers.getValue(Color.RED)
        val orangeLab = cal.centers.getValue(Color.ORANGE)
        val boundaryLab =
            Lab(
                (redLab.l + orangeLab.l) / 2.0,
                (redLab.a + orangeLab.a) / 2.0,
                (redLab.b + orangeLab.b) / 2.0,
            )
        val baseEvidence = requireNotNull(baseReview.evidence)
        val perturbedStickers = baseReview.stickers.toMutableList().also { it[redIdx] = Color.ORANGE }
        val perturbedLabs = baseEvidence.labs.toMutableList().also { it[redIdx] = boundaryLab }
        val perturbedEvidence = baseEvidence.copy(labs = perturbedLabs)

        val suspects = SuspectRanking.rank(perturbedStickers, perturbedEvidence)
        assertTrue(suspects.isNotEmpty(), "SuspectRanking must flag the boundary sticker")
        assertEquals(redIdx, suspects.first().index)
        assertEquals(Color.RED, suspects.first().likely)
    }

    @Test
    fun `Story 10-17 assembleHandover keeps confirmed continuous faces untouched without reclassifying`() {
        // Put bogus Labs on kept faces (U, F, R) that would classify as BLUE if reclassified,
        // proving assembleHandover NEVER reclassifies confirmed continuous faces!
        val keptFaces = listOf(Face.U, Face.F, Face.R)
        val bogusLab = RecognitionFixtures.LABS.getValue(Color.BLUE)
        val handoffFaces =
            keptFaces.associateWith { f ->
                val colors = scrambledNet.getValue(f)
                ContinuousFaceHandoff(
                    face = f,
                    colors = colors,
                    confidence = List(9) { StickerConfidence.CONFIDENT },
                    labs = List(9) { idx -> if (idx == 4) RecognitionFixtures.LABS.getValue(Color.home(f)) else bogusLab },
                    orientationLocked = true,
                )
            }
        val handoff = ContinuousScanHandoff(faces = handoffFaces, calibration = null, globalAnchored = true)

        val addedFaces = listOf(Face.D, Face.L, Face.B)
        val addedSamples =
            addedFaces.map { f ->
                // Rotate sample by 90 deg and omit upNeighborColor so ScanAssembler must orient them!
                val rotatedColors = ScanAssembler.rotateClockwise(scrambledNet.getValue(f), 3)
                RecognitionFixtures.sample(
                    center = Color.home(f),
                    up = null,
                    stickers = rotatedColors.map { RecognitionFixtures.LABS.getValue(it) },
                    face = f,
                )
            }

        val result = ScanAssembler.assembleHandover(handoff, addedSamples, saved = null)
        val full = assertInstanceOf(ScanResult.Full::class.java, result)
        assertEquals(scrambledFacelets, full.facelets)
        assertTrue(full.confidence.all { it == StickerConfidence.CONFIDENT })
    }

    @Test
    fun `Story 10-17 assembleHandover aligns spun Labs and rejects unoriented partial faces`() {
        // Single isolated face U rotated 90 deg CW from canonical net (orientationLocked = false)
        // Distinct per-cell Labs + null at index 0 (unrotated) to verify fallback Lab is NOT double-rotated
        val rawUColors = ScanAssembler.rotateClockwise(scrambledNet.getValue(Face.U), 1)
        val rawULabs =
            List(9) { idx ->
                if (idx == 0) null else Lab(50.0 + idx * 3.0, idx.toDouble(), -idx.toDouble())
            }
        val isolatedU =
            ContinuousFaceHandoff(
                face = Face.U,
                colors = rawUColors,
                confidence = List(9) { StickerConfidence.CONFIDENT },
                labs = rawULabs,
                orientationLocked = false,
            )
        val handoff =
            ContinuousScanHandoff(
                faces = mapOf(Face.U to isolatedU),
                calibration = CalibrationCenters(RecognitionFixtures.LABS),
                globalAnchored = true,
            )

        val otherSamples =
            (Face.entries - Face.U).map { f ->
                val rotColors = ScanAssembler.rotateClockwise(scrambledNet.getValue(f), 3)
                RecognitionFixtures.sample(
                    center = Color.home(f),
                    up = null,
                    stickers = rotColors.map { RecognitionFixtures.LABS.getValue(it) },
                    face = f,
                )
            }
        val full = assertInstanceOf(ScanResult.Full::class.java, ScanAssembler.assembleHandover(handoff, otherSamples))
        assertEquals(scrambledFacelets, full.facelets)
        val fullLabs = requireNotNull(full.labs)
        // rawUColors was rotated 1 CW from canonical, so assembler rotates by 3 CW (= 1 CCW).
        // Unrotated index 0 maps under 3 CW turns to canonical index 6!
        assertEquals(RecognitionFixtures.LABS.getValue(scrambledNet.getValue(Face.U)[6]), fullLabs[6])
        // Unrotated index 1 maps under 3 CW turns to canonical index 3!
        assertEquals(rawULabs[1], fullLabs[3])
        // Added rotated faces must have their Labs rotated to match canonical sticker colors
        for (f in Face.entries - Face.U) {
            val expectedLabs = scrambledNet.getValue(f).map { RecognitionFixtures.LABS.getValue(it) }
            assertEquals(expectedLabs, fullLabs.subList(f.ordinal * 9, f.ordinal * 9 + 9))
        }

        // Partial handover with unoriented kept face marks it NO_POSE rather than emitting wrong rotation
        val partialUnoriented = assertInstanceOf(ScanResult.Partial::class.java, ScanAssembler.assembleHandover(handoff, emptyList()))
        assertTrue(Face.U !in partialUnoriented.observation.faces)
        assertEquals(ScanGap.NO_POSE, partialUnoriented.gaps[Face.U])
        for (f in Face.entries - Face.U) {
            assertEquals(ScanGap.MISSING, partialUnoriented.gaps[f])
        }

        // Partial handover with orientationLocked = true kept face keeps it in observation.faces
        val lockedHandoff =
            handoff.copy(
                faces =
                    mapOf(Face.U to isolatedU.copy(colors = scrambledNet.getValue(Face.U), orientationLocked = true)),
            )
        val partialLocked = assertInstanceOf(ScanResult.Partial::class.java, ScanAssembler.assembleHandover(lockedHandoff, emptyList()))
        assertEquals(setOf(Face.U), partialLocked.observation.faces.keys)
        for (f in Face.entries - Face.U) {
            assertEquals(ScanGap.MISSING, partialLocked.gaps[f])
        }
    }

    @Test
    fun `real mapper partial export integrates end-to-end with assembleHandover and toFallbackStickers`() {
        // 0-face empty mapper export
        val emptyMapper = ContinuousFaceletMapper()
        val emptyExport = assertInstanceOf(ContinuousScanExport.Partial::class.java, emptyMapper.export())
        assertFalse(emptyExport.handoff.globalAnchored)
        assertEquals(List(54) { null }, emptyExport.handoff.toFallbackStickers())
        val emptyPartial =
            assertInstanceOf(ScanResult.Partial::class.java, ScanAssembler.assembleHandover(emptyExport.handoff, emptyList()))
        assertEquals(Face.entries.toSet(), emptyPartial.gaps.keys)

        // 1-face unanchored export
        val oneFaceMapper = ContinuousFaceletMapper()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val obs =
            QuadObservation(
                quad = quad,
                cellRgbs = List(9) { RgbColor(40.0, 175.0, 75.0) },
                cellQualities = List(9) { 0.9 },
                sharpness = 0.9,
                hasGlare = false,
            )
        repeat(4) { i -> oneFaceMapper.processFrame(i, listOf(obs)) }
        val unanchoredExport = assertInstanceOf(ContinuousScanExport.Partial::class.java, oneFaceMapper.export())
        assertFalse(unanchoredExport.handoff.globalAnchored)
        assertEquals(1, unanchoredExport.handoff.faces.size)
        assertEquals(List(54) { null }, unanchoredExport.handoff.toFallbackStickers())
        // assembleHandover ignores unanchored faces
        val unanchoredRes =
            assertInstanceOf(ScanResult.Partial::class.java, ScanAssembler.assembleHandover(unanchoredExport.handoff, emptyList()))
        assertTrue(unanchoredRes.observation.faces.isEmpty())

        // 3-5 face anchored real mapper export stopped mid-S1, handed over to Guided Scan with standard D65 CIELAB samples
        val cfg = SyntheticCubeStream.buildScenarios()[0] // S1: scrambled stickered
        val s1Net = SyntheticCubeStream.stateToNet(cfg.state)
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val partialMapper = ContinuousFaceletMapper()
        var partialHandoff: ContinuousScanHandoff? = null
        for (fr in frames) {
            val snap = partialMapper.processFrame(fr.frameIndex, fr.detections)
            if (snap.globalAnchored && snap.mappedFacesCount in 3..5) {
                val exp = assertInstanceOf(ContinuousScanExport.Partial::class.java, partialMapper.export())
                if (exp.handoff.faces.size in 3..5) {
                    partialHandoff = exp.handoff
                    break
                }
            }
        }
        val handoff = requireNotNull(partialHandoff) { "Expected anchored 3..5 face partial handoff during S1" }
        assertTrue(handoff.globalAnchored)
        assertFalse(handoff.isComplete)
        // toFallbackStickers sets anchored centers and never guesses unclassified cells
        val editorSeed = handoff.toFallbackStickers()
        for (f in handoff.faces.keys) {
            assertEquals(Color.home(f), editorSeed[f.ordinal * 9 + 4])
        }

        // Capture remaining missing faces in Guided Scan using standard D65 CIELAB
        val standardPalette =
            mapOf(
                Color.WHITE to ContinuousColorModel.rgbToLab(RgbColor(236.0, 238.0, 240.0)),
                Color.YELLOW to ContinuousColorModel.rgbToLab(RgbColor(235.0, 212.0, 32.0)),
                Color.GREEN to ContinuousColorModel.rgbToLab(RgbColor(34.0, 168.0, 72.0)),
                Color.BLUE to ContinuousColorModel.rgbToLab(RgbColor(28.0, 86.0, 206.0)),
                Color.RED to ContinuousColorModel.rgbToLab(RgbColor(204.0, 38.0, 42.0)),
                Color.ORANGE to ContinuousColorModel.rgbToLab(RgbColor(238.0, 118.0, 28.0)),
            )
        val remainingFaces = Face.entries - handoff.faces.keys
        val guidedAdded =
            remainingFaces.map { f ->
                val rotated = ScanAssembler.rotateClockwise(s1Net.getValue(f), 1)
                RecognitionFixtures.sample(
                    center = Color.home(f),
                    up = null,
                    stickers = rotated.map { standardPalette.getValue(it) },
                    face = f,
                )
            }
        val assembled = ScanAssembler.assembleHandover(handoff, guidedAdded, saved = null)
        val full = assertInstanceOf(ScanResult.Full::class.java, assembled)
        assertEquals(cfg.state.toFacelets(), full.facelets)
        assertTrue(full.confidence.all { it == StickerConfidence.CONFIDENT })
        assertNotNull(full.labs)
    }

    @Test
    fun `assembleHandover never throws on partial fallbackCenters and downgrades ambiguous spins`() {
        // 6 kept faces, calibration = null, one face has null center Lab -> fallbackCenters is partial (5 colors).
        // Must return ScanResult.Full with labs = null rather than throwing NoSuchElementException.
        val partialLabFaces =
            Face.entries.associateWith { f ->
                ContinuousFaceHandoff(
                    face = f,
                    colors = scrambledNet.getValue(f),
                    confidence = List(9) { StickerConfidence.CONFIDENT },
                    labs = List(9) { idx -> if (f == Face.B && idx == 4) null else RecognitionFixtures.LABS.getValue(Color.home(f)) },
                    orientationLocked = true,
                )
            }
        val sixKeptPartialCal = ContinuousScanHandoff(faces = partialLabFaces, calibration = null, globalAnchored = true)
        val fullNoCal = assertInstanceOf(ScanResult.Full::class.java, ScanAssembler.assembleHandover(sixKeptPartialCal, emptyList()))
        assertEquals(scrambledFacelets, fullNoCal.facelets)
        assertNull(fullNoCal.labs)

        // Ambiguous/unresolved spin: corrupt one sticker on locked U so no valid state exists;
        // spinning unposed added face D differs across best candidates -> downgraded to UNCERTAIN while locked U stays CONFIDENT.
        val brokenU = scrambledNet.getValue(Face.U).toMutableList().also { it[0] = Color.YELLOW }
        val lockedFaces =
            (Face.entries - Face.D).associateWith { f ->
                ContinuousFaceHandoff(
                    face = f,
                    colors = if (f == Face.U) brokenU else scrambledNet.getValue(f),
                    confidence = List(9) { StickerConfidence.CONFIDENT },
                    labs = List(9) { RecognitionFixtures.LABS.getValue(Color.home(f)) },
                    orientationLocked = true,
                )
            }
        val dWrong =
            List(9) { i ->
                if (i == 4) {
                    Color.YELLOW
                } else if (i == 0) {
                    Color.WHITE
                } else {
                    Color.YELLOW
                }
            }
        val dSample =
            RecognitionFixtures.sample(
                center = Color.YELLOW,
                up = null,
                stickers = dWrong.map { RecognitionFixtures.LABS.getValue(it) },
                face = Face.D,
            )
        val ambigResult =
            assertInstanceOf(
                ScanResult.Full::class.java,
                ScanAssembler.assembleHandover(
                    ContinuousScanHandoff(lockedFaces, CalibrationCenters(RecognitionFixtures.LABS), globalAnchored = true),
                    listOf(dSample),
                ),
            )
        assertTrue(ambigResult.confidence.subList(0, 9).all { it == StickerConfidence.CONFIDENT })
        val dConf = ambigResult.confidence.subList(Face.D.ordinal * 9, Face.D.ordinal * 9 + 9)
        assertTrue(dConf.filterIndexed { i, _ -> i != 4 }.all { it == StickerConfidence.UNCERTAIN })
    }

    @Test
    fun `ScanReview from ContinuousScanHandoff enforces unanchored uncertain and invalid calibration guards`() {
        val validCal = CalibrationCenters(RecognitionFixtures.LABS)
        // 1. globalAnchored = false -> all 48 non-center stickers blank, evidence.labs all null
        val unanchoredHandoff =
            ContinuousScanHandoff(
                faces =
                    mapOf(
                        Face.U to
                            ContinuousFaceHandoff(
                                face = Face.U,
                                colors = scrambledNet.getValue(Face.U),
                                confidence = List(9) { StickerConfidence.CONFIDENT },
                                labs = List(9) { RecognitionFixtures.LABS.getValue(Color.WHITE) },
                                orientationLocked = false,
                            ),
                    ),
                calibration = validCal,
                globalAnchored = false,
            )
        val unanchoredReview = ScanReview.from(unanchoredHandoff)
        assertEquals(48, unanchoredReview.blanks.size)
        assertNotNull(unanchoredReview.evidence)
        assertTrue(unanchoredReview.evidence!!.labs.all { it == null })

        // 2. Partial with UNCERTAIN / null stickers and missing faces -> blanks kept and first blank selected
        val partialFaces =
            mapOf(
                Face.U to
                    ContinuousFaceHandoff(
                        face = Face.U,
                        colors = scrambledNet.getValue(Face.U).mapIndexed { i, c -> if (i == 1) null else c },
                        confidence = List(9) { i -> if (i == 0 || i == 1) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT },
                        labs = List(9) { RecognitionFixtures.LABS.getValue(Color.WHITE) },
                        orientationLocked = true,
                    ),
            )
        val partialReview = ScanReview.from(ContinuousScanHandoff(partialFaces, calibration = null, globalAnchored = true))
        assertNull(partialReview.evidence)
        assertNull(partialReview.stickers[0])
        assertNull(partialReview.stickers[1])
        assertEquals(scrambledNet.getValue(Face.U)[2], partialReview.stickers[2])
        assertEquals(0, partialReview.selected)

        // 3. Incomplete/invalid calibration -> review.evidence == null without throwing
        val badCal = CalibrationCenters(mapOf(Color.WHITE to Lab(Double.NaN, 0.0, 0.0)))
        val badCalReview = ScanReview.from(ContinuousScanHandoff(partialFaces, calibration = badCal, globalAnchored = true))
        assertNull(badCalReview.evidence)
    }

    @Test
    fun `issue 321 toFallbackStickers seeds confirmed confident stickers only and leaves unmapped or unanchored null`() {
        val emptyHandoff = ContinuousScanHandoff(emptyMap(), calibration = null, globalAnchored = false)
        assertEquals(List(54) { null }, emptyHandoff.toFallbackStickers())

        val unanchored =
            ContinuousScanHandoff(
                faces =
                    mapOf(
                        Face.F to
                            ContinuousFaceHandoff(
                                face = Face.F,
                                colors = scrambledNet.getValue(Face.F),
                                confidence = List(9) { StickerConfidence.CONFIDENT },
                                labs = List(9) { null },
                                orientationLocked = false,
                            ),
                    ),
                calibration = null,
                globalAnchored = false,
            )
        assertEquals(List(54) { null }, unanchored.toFallbackStickers())

        val partialFaces =
            mapOf(
                Face.U to
                    ContinuousFaceHandoff(
                        face = Face.U,
                        colors = scrambledNet.getValue(Face.U),
                        confidence = List(9) { i -> if (i == 0) StickerConfidence.UNCERTAIN else StickerConfidence.CONFIDENT },
                        labs = List(9) { null },
                        orientationLocked = true,
                    ),
                Face.F to
                    ContinuousFaceHandoff(
                        face = Face.F,
                        colors = scrambledNet.getValue(Face.F),
                        confidence = List(9) { StickerConfidence.CONFIDENT },
                        labs = List(9) { null },
                        orientationLocked = true,
                    ),
            )
        val partial = ContinuousScanHandoff(faces = partialFaces, calibration = null, globalAnchored = true)
        assertFalse(partial.isComplete)
        val fallback = partial.toFallbackStickers()
        assertEquals(54, fallback.size)
        assertNull(fallback[0], "Uncertain sticker must stay null")
        for (i in 1 until 9) assertEquals(scrambledNet.getValue(Face.U)[i], fallback[i])
        for (i in 9 until 18) assertNull(fallback[i], "Unmapped R face must stay null")
        for (i in 18 until 27) assertEquals(scrambledNet.getValue(Face.F)[i - 18], fallback[i])
        for (i in 27 until 54) assertNull(fallback[i], "Unmapped D/L/B faces must stay null")
    }
}
