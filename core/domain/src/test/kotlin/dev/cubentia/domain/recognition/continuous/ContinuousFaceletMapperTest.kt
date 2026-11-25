package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.IcuArguments
import dev.cubentia.domain.content.IcuResult
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuousFaceletMapperTest {
    @Test
    fun `cube geometry tables and local physical invariants catch impossible edges, chirality, duplicates and counts`() {
        assertEquals(12, ContinuousCubeGeometry.EDGE_CUBIES.size)
        assertEquals(8, ContinuousCubeGeometry.CORNER_CUBIES.size)
        assertEquals(24, ContinuousCubeGeometry.FACE_ADJACENCY.size)
        assertEquals(24, ContinuousCubeGeometry.SO3_COUNT)

        val solved = SyntheticCubeStream.stateToNet(CubeState.SOLVED)
        assertTrue(ContinuousCubeGeometry.checkLocalInvariants(solved).isEmpty())
        assertTrue(ContinuousCubeGeometry.isFaceRotationallySymmetric(solved.getValue(Face.D)))

        val scr = SyntheticCubeStream.stateToNet(SyntheticCubeStream.scramble("R U R' F' L2 D B' U2"))
        assertTrue(ContinuousCubeGeometry.checkLocalInvariants(scr).isEmpty())
        assertFalse(ContinuousCubeGeometry.isFaceRotationallySymmetric(scr.getValue(Face.F)))

        // Opposite-color edge (WHITE+YELLOW on UF edge)
        val badEdge = solved.mapValues { it.value.toMutableList<Color?>() }
        badEdge.getValue(Face.F)[1] = Color.YELLOW
        val edgeKinds = ContinuousCubeGeometry.checkLocalInvariants(badEdge).map { it.kind }.toSet()
        assertTrue(LocalInvariantKind.IMPOSSIBLE_EDGE in edgeKinds)
        assertTrue(LocalInvariantKind.COLOR_COUNT_EXCEEDED in edgeKinds)

        // Mirrored corner chirality (swapping F[2] and R[0] on UFR corner)
        val badCorner = solved.mapValues { it.value.toMutableList<Color?>() }
        val tmp = badCorner.getValue(Face.F)[2]
        badCorner.getValue(Face.F)[2] = badCorner.getValue(Face.R)[0]
        badCorner.getValue(Face.R)[0] = tmp
        val cornerKinds = ContinuousCubeGeometry.checkLocalInvariants(badCorner).map { it.kind }.toSet()
        assertTrue(LocalInvariantKind.INVALID_CORNER_CHIRALITY in cornerKinds)

        // Duplicate edge cubie
        val dupEdge = solved.mapValues { it.value.toMutableList<Color?>() }
        dupEdge.getValue(Face.U)[5] = Color.WHITE
        dupEdge.getValue(Face.R)[1] = Color.GREEN // duplicates UF (WHITE, GREEN)
        val dupEdgeKinds = ContinuousCubeGeometry.checkLocalInvariants(dupEdge).map { it.kind }.toSet()
        assertTrue(LocalInvariantKind.DUPLICATE_EDGE in dupEdgeKinds)

        // Impossible corner pair (2 of 3 observed, opposite colors)
        val partialBadCorner = Face.entries.associateWith { MutableList<Color?>(9) { null } }
        partialBadCorner.getValue(Face.U)[8] = Color.WHITE
        partialBadCorner.getValue(Face.R)[0] = Color.YELLOW
        val pairKinds = ContinuousCubeGeometry.checkLocalInvariants(partialBadCorner).map { it.kind }.toSet()
        assertTrue(LocalInvariantKind.IMPOSSIBLE_CORNER_PAIR in pairKinds)
    }

    @Test
    fun `every ContinuousScanCue resolves in shipped en dot json with matching ICU args and calm copy`() {
        val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
        val strings = loaded.bundle.strings
        val cues =
            listOf(
                ContinuousScanCue.BringIntoView,
                ContinuousScanCue.TurnRemaining(3),
                ContinuousScanCue.TiltAdjacent,
                ContinuousScanCue.KeepTurningRealign,
                ContinuousScanCue.TiltAwayLight,
                ContinuousScanCue.TiltAwayLightRemaining(2),
                ContinuousScanCue.ResolveConflict(Face.R),
                ContinuousScanCue.LayerTwistDetected,
                ContinuousScanCue.AllVerified,
                ContinuousScanCue.TiltRemainingStickers(42),
            )
        for (cue in cues) {
            val text = cue.toText()
            val message = requireNotNull(strings[text.key]) { "Missing key ${text.key}" }
            assertFalse('!' in message, "Voice guide forbids '!': $message")
            assertFalse("rubik" in message.lowercase(), "Trademark guard forbids rubik: $message")
            assertEquals(IcuResult.Parsed(text.args.keys), IcuArguments.parse(message), text.key)
        }
    }

    @Test
    fun `empty frames clear tracking after regrip and reset clears all mapper state`() {
        val mapper = ContinuousFaceletMapper()
        val s0 = mapper.processFrame(0, emptyList())
        assertInstanceOf(ContinuousScanSnapshot.Scanning::class.java, s0)
        assertEquals(ContinuousScanCue.BringIntoView, s0.cue)
        assertEquals(0, s0.committedCount)

        // Feed a single clean square face across several frames, then verify reset()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val obs =
            QuadObservation(
                quad = quad,
                cellRgbs = List(9) { RgbColor(40.0, 175.0, 75.0) },
                cellQualities = List(9) { 0.9 },
                sharpness = 0.9,
                hasGlare = false,
            )
        mapper.processFrame(1, listOf(obs))
        val s2 = mapper.processFrame(2, listOf(obs))
        assertTrue(s2.trackedQuads.isNotEmpty())
        val s3 = mapper.processFrame(3, listOf(obs))
        assertEquals(1, s3.mappedFacesCount)

        // Center-color continuity lock: a sudden jump from GREEN to RED at the same quad rejects tracking match
        val jumpedRed =
            obs.copy(
                cellRgbs = List(9) { RgbColor(210.0, 40.0, 45.0) },
            )
        val sJump = mapper.processFrame(4, listOf(jumpedRed))
        assertTrue(sJump.pendingFragmentFaces >= 1, "Sudden center jump must break master track into pending fragment")

        mapper.reset()
        val afterReset = mapper.processFrame(0, emptyList())
        assertEquals(0, afterReset.mappedFacesCount)
        assertEquals(0, afterReset.committedCount)
        assertEquals(0, afterReset.pendingFragmentFaces)
        assertFalse(afterReset.globalAnchored)
        assertFalse(afterReset.twistDetected)
    }

    @Test
    fun `glare washout on outer row does not falsely trigger mid-scan twist`() {
        val mapper = ContinuousFaceletMapper()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val green = RgbColor(34.0, 168.0, 72.0)
        val clean =
            QuadObservation(
                quad = quad,
                cellRgbs = List(9) { green },
                cellQualities = List(9) { 0.9 },
                sharpness = 0.95,
                hasGlare = false,
            )
        for (f in 0 until 10) {
            mapper.processFrame(f, listOf(clean))
        }
        // Bright desaturated washout on top row (high brightness, low chroma) — must NOT trigger twist!
        val washedOut = RgbColor(235.0, 240.0, 236.0)
        val glareObs =
            clean.copy(
                cellRgbs = List(9) { i -> if (i in 0..2) washedOut else green },
                hasGlare = false,
            )
        var last: ContinuousScanSnapshot = mapper.processFrame(10, listOf(glareObs))
        for (f in 11 until 18) {
            last = mapper.processFrame(f, listOf(glareObs))
        }
        assertFalse(last.twistDetected, "Glare washout must not trigger false layer twist")
    }

    @Test
    fun `processFrame reopens weakest conflicting slot emits ResolveConflict cue and recovers on clean frames`() {
        // Build a synthetic 6-face stream preserving exact 8-per-color counts while swapping
        // F[1] (GREEN->YELLOW) and D[1] (YELLOW->GREEN) so UF edge is impossible WHITE+YELLOW.
        // Give F[1] strictly weaker quality than U[7] so the mapper reopens F[1] and emits ResolveConflict(Face.F),
        // then feed clean frames with high quality so the conflict resolves cleanly.
        val solved = CubeState.SOLVED
        val cfg = SyntheticCubeStream.ScenarioConfig(name = "conflict_test", state = solved, jitterPx = 0.0, nFrames = 120)
        val frames = SyntheticCubeStream.generateSequence(cfg)

        val yellowRgb = RgbColor(235.0, 212.0, 32.0)
        val greenRgb = RgbColor(34.0, 168.0, 72.0)
        val mapper = ContinuousFaceletMapper()
        var sawConflictSnap: ContinuousScanSnapshot? = null
        var fSeen = 0
        var dSeen = 0

        for (fr in frames) {
            val mutatedDets =
                fr.detections.map { det ->
                    val match =
                        fr.visibleFaces.entries.minByOrNull { (_, canonQuad) ->
                            (0 until 4).minOf { s ->
                                val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                                (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], canonQuad.corners[k]) }
                            }
                        } ?: return@map det
                    val face = match.key
                    if (face != Face.F && face != Face.D) return@map det
                    val shift =
                        (0 until 4).minBy { s ->
                            val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                            (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], match.value.corners[k]) }
                        }
                    val alignedRgbs = ContinuousColorModel.rotateGridCcw(det.cellRgbs, shift).toMutableList()
                    val alignedQuals = ContinuousColorModel.rotateGridCcw(det.cellQualities, shift).toMutableList()
                    if (face == Face.F) {
                        fSeen++
                        alignedRgbs[1] = yellowRgb
                        alignedQuals[1] = if (fSeen <= 4) 0.16 else 0.01
                    } else {
                        dSeen++
                        alignedRgbs[1] = greenRgb
                        alignedQuals[1] = if (dSeen <= 4) 0.18 else 0.01
                    }
                    det.copy(
                        cellRgbs = ContinuousColorModel.rotateGridCw(alignedRgbs, shift),
                        cellQualities = ContinuousColorModel.rotateGridCw(alignedQuals, shift),
                    )
                }
            val snap = mapper.processFrame(fr.frameIndex, mutatedDets)
            if (FaceSlot(Face.F, 1) in snap.conflictedSlots && sawConflictSnap == null) {
                sawConflictSnap = snap
            }
        }
        val conflictSnap = requireNotNull(sawConflictSnap) { "Expected processFrame to reopen weaker slot F[1]" }
        assertNull(conflictSnap.net.getValue(Face.F)[1], "Reopened slot must be null in snapshot net")
        val cue = assertInstanceOf(ContinuousScanCue.ResolveConflict::class.java, conflictSnap.cue)
        assertEquals(Face.F, cue.face)
        assertEquals("green", cue.toText().args["color"])

        // Feed clean high-quality frames for F and D -> conflict resolves to Complete!
        var recovered: ContinuousScanSnapshot = conflictSnap
        for (k in frames.indices) {
            recovered = mapper.processFrame(frames.size + k, frames[k].detections)
            if (recovered is ContinuousScanSnapshot.Complete) break
        }
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, recovered)
        assertTrue(recovered.conflictedSlots.isEmpty())
    }

    @Test
    fun `global parity failure at 54 of 54 never emits Complete or AllVerified`() {
        // Construct a locally valid 54-sticker cube with a single twisted UFR corner (fails CubeValidator)
        val cfg = SyntheticCubeStream.ScenarioConfig(name = "parity_test", state = CubeState.SOLVED, jitterPx = 0.0, nFrames = 120)
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val whiteRgb = RgbColor(236.0, 238.0, 240.0)
        val greenRgb = RgbColor(34.0, 168.0, 72.0)
        val redRgb = RgbColor(204.0, 38.0, 42.0)

        val mapper = ContinuousFaceletMapper()
        var lastSnap: ContinuousScanSnapshot? = null
        for (fr in frames) {
            val twistedDets =
                fr.detections.map { det ->
                    // Identify canonical face from visibleFaces by minimum corner distance across shifts
                    val match =
                        fr.visibleFaces.entries.minByOrNull { (_, canonQuad) ->
                            (0 until 4).minOf { s ->
                                val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                                (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], canonQuad.corners[k]) }
                            }
                        } ?: return@map det
                    val face = match.key
                    if (face != Face.U && face != Face.F && face != Face.R) return@map det
                    val shift =
                        (0 until 4).minBy { s ->
                            val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                            (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], match.value.corners[k]) }
                        }
                    val alignedRgbs = ContinuousColorModel.rotateGridCcw(det.cellRgbs, shift).toMutableList()
                    // Twist UFR corner CW: U[8] WHITE->GREEN, F[2] GREEN->RED, R[0] RED->WHITE
                    if (face == Face.U) {
                        alignedRgbs[8] = greenRgb
                    } else if (face == Face.F) {
                        alignedRgbs[2] = redRgb
                    } else {
                        alignedRgbs[0] = whiteRgb
                    }
                    det.copy(cellRgbs = ContinuousColorModel.rotateGridCw(alignedRgbs, shift))
                }
            lastSnap = mapper.processFrame(fr.frameIndex, twistedDets)
        }
        val finalSnap = requireNotNull(lastSnap)
        assertEquals(54, finalSnap.committedCount)
        assertInstanceOf(ContinuousScanSnapshot.Scanning::class.java, finalSnap)
        assertNotEquals(ContinuousScanCue.AllVerified, finalSnap.cue)
        assertInstanceOf(ContinuousScanExport.Partial::class.java, mapper.export())
    }

    @Test
    fun `non-finite and non-convex detections are ignored without poisoning logAwb and bounds checks fail fast`() {
        val mapper = ContinuousFaceletMapper()
        val goodQuad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val nanQuad = Quad2D(listOf(Point2D(Double.NaN, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val bowtieQuad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 270.0), Point2D(320.0, 150.0), Point2D(200.0, 270.0)))
        val green = RgbColor(34.0, 168.0, 72.0)

        assertEquals(0.0 to 0.0, ProjectiveGeometry.quadQuality(nanQuad))
        assertFalse(ProjectiveGeometry.isValidFaceQuad(goodQuad, Double.NaN))
        assertFalse(ProjectiveGeometry.isValidFaceQuad(goodQuad, -5.0))

        val nanQuadObs = QuadObservation(nanQuad, List(9) { green }, List(9) { 0.9 }, 0.9, hasGlare = false)
        val nanRgbObs = QuadObservation(goodQuad, List(9) { RgbColor(Double.NaN, 100.0, 50.0) }, List(9) { 0.9 }, 0.9, hasGlare = false)
        val bowtieObs = QuadObservation(bowtieQuad, List(9) { green }, List(9) { 0.9 }, 0.9, hasGlare = false)
        val cleanObs = QuadObservation(goodQuad, List(9) { green }, List(9) { 0.9 }, 0.9, hasGlare = false)

        val badSnap = mapper.processFrame(0, listOf(nanQuadObs, nanRgbObs, bowtieObs))
        assertTrue(badSnap.trackedQuads.isEmpty())
        mapper.processFrame(1, listOf(cleanObs))
        mapper.processFrame(2, listOf(cleanObs))
        mapper.processFrame(3, listOf(nanRgbObs, bowtieObs, cleanObs))
        assertTrue(mapper.masterLogAwb.all { it.isFinite() })

        // Bounds checks on ContinuousCubeGeometry
        assertThrows(IllegalArgumentException::class.java) { ContinuousCubeGeometry.rotateFace(Face.U, 24) }
        assertThrows(IllegalArgumentException::class.java) { ContinuousCubeGeometry.rotateSlot(Face.U, 9, 0) }
        assertThrows(IllegalArgumentException::class.java) { ContinuousCubeGeometry.rotateSlot(Face.U, 0, -1) }
    }

    @Test
    fun `FaceLatticeFitter output quads feed directly into ContinuousFaceletMapper`() {
        val cfg = SyntheticCubeStream.buildScenarios()[0]
        val frame = SyntheticCubeStream.generateSequence(cfg)[25]
        val unit = listOf(Point2D(0.0, 0.0), Point2D(1.0, 0.0), Point2D(1.0, 1.0), Point2D(0.0, 1.0))
        val gridUv = List(9) { idx -> Point2D((idx % 3) / 3.0 + 1.0 / 6.0, (idx / 3) / 3.0 + 1.0 / 6.0) }
        val centers = mutableListOf<Point2D>()
        for (q in frame.visibleFaces.values) {
            val h = requireNotNull(ProjectiveGeometry.findHomography(unit, q.corners))
            for (uv in gridUv) centers.add(requireNotNull(ProjectiveGeometry.perspectiveTransform(h, uv)))
        }
        val fitted = FaceLatticeFitter.fit(centers, maxFaces = 3, minInliers = 6)
        assertEquals(3, fitted.size)

        val mapper = ContinuousFaceletMapper()
        val obsList =
            fitted.mapIndexed { idx, f ->
                val rgb = listOf(RgbColor(236.0, 238.0, 240.0), RgbColor(34.0, 168.0, 72.0), RgbColor(204.0, 38.0, 42.0))[idx]
                QuadObservation(f.quad, List(9) { rgb }, List(9) { 0.9 }, sharpness = 0.95, hasGlare = false, score = f.score)
            }
        val snap = mapper.processFrame(0, obsList)
        assertEquals(3, snap.trackedQuads.size)
        val snap2 = mapper.processFrame(1, obsList)
        assertEquals(3, snap2.mappedFacesCount)
    }
}
