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
import kotlin.math.abs

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

        // Duplicate corner cubie (duplicate UFR (WHITE, RED, GREEN) onto UFL with valid CW chirality)
        val dupCorner = solved.mapValues { it.value.toMutableList<Color?>() }
        dupCorner.getValue(Face.U)[6] = Color.WHITE
        dupCorner.getValue(Face.F)[0] = Color.RED
        dupCorner.getValue(Face.L)[2] = Color.GREEN
        val dupCornerViols =
            ContinuousCubeGeometry
                .checkLocalInvariants(
                    dupCorner,
                ).filter { it.kind == LocalInvariantKind.DUPLICATE_CORNER }
        assertTrue(dupCornerViols.isNotEmpty(), "Expected DUPLICATE_CORNER invariant violation")
        assertEquals(6, dupCornerViols.first().slots.size)
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
        assertInstanceOf(ContinuousScanCue.ResolveConflict::class.java, finalSnap.cue)
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

    @Test
    fun `counter-clockwise convex quads are rejected and isolated clutter across empty frames never initializes master`() {
        val mapper = ContinuousFaceletMapper()
        val cwQuad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val ccwQuad = Quad2D(cwQuad.corners.reversed())
        val green = RgbColor(34.0, 168.0, 72.0)

        val ccwObs = QuadObservation(ccwQuad, List(9) { green }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        assertTrue(mapper.processFrame(0, listOf(ccwObs)).trackedQuads.isEmpty())
        assertTrue(mapper.processFrame(1, listOf(ccwObs)).trackedQuads.isEmpty())

        // Single CW quad separated by an empty frame must reset hasPrevUnattached and not initialize master
        val cwObs = QuadObservation(cwQuad, List(9) { green }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        assertTrue(mapper.processFrame(2, listOf(cwObs)).trackedQuads.isEmpty())
        assertTrue(mapper.processFrame(3, emptyList()).trackedQuads.isEmpty())
        assertTrue(mapper.processFrame(4, listOf(cwObs)).trackedQuads.isEmpty())
    }

    @Test
    fun `historical center-color lock catches color jump across a low-quality bridge frame`() {
        val mapper = ContinuousFaceletMapper()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val green = RgbColor(34.0, 168.0, 72.0)
        val red = RgbColor(204.0, 38.0, 42.0)

        val greenObs = QuadObservation(quad, List(9) { green }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        repeat(4) { i -> mapper.processFrame(i, listOf(greenObs)) }

        // Low center quality (0.10 <= 0.20) bypasses frame-to-frame check on the next frame
        val lowQBridge =
            QuadObservation(
                quad = quad,
                cellRgbs = List(9) { green },
                cellQualities = List(9) { idx -> if (idx == 4) 0.10 else 0.9 },
                sharpness = 0.9,
                hasGlare = false,
            )
        mapper.processFrame(4, listOf(lowQBridge))

        // High-quality RED on frame 5 must still be rejected by historical center lock (> 26.0)
        val redObs = QuadObservation(quad, List(9) { red }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        val sJump = mapper.processFrame(5, listOf(redObs))
        assertTrue(sJump.pendingFragmentFaces >= 1, "Historical center lock must reject RED across low-quality bridge")
    }

    @Test
    fun `pending promotion replaces unanchored master and immediate tracking resumes after pending tracked clears`() {
        val mapper = ContinuousFaceletMapper()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val blue = RgbColor(28.0, 86.0, 206.0)
        val oneFace = QuadObservation(quad, List(9) { blue }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)

        // Seed unanchored 1-face master
        repeat(4) { i -> mapper.processFrame(i, listOf(oneFace)) }
        // Clear master.tracked with 3 empty frames
        repeat(3) { i -> mapper.processFrame(4 + i, emptyList()) }

        // Create a 1-face confirmed pending fragment, then clear pending.tracked via 3 empty frames
        val orange = RgbColor(238.0, 118.0, 28.0)
        val otherFace = QuadObservation(quad, List(9) { orange }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        mapper.processFrame(7, listOf(otherFace))
        mapper.processFrame(8, listOf(otherFace))
        repeat(3) { i -> mapper.processFrame(9 + i, emptyList()) }

        // Very first frame after pending.tracked cleared must track immediately (no 2-frame BringIntoView blackout)
        val cfg = SyntheticCubeStream.buildScenarios()[0]
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val resumeSnap = mapper.processFrame(12, frames[0].detections)
        assertTrue(resumeSnap.trackedQuads.isNotEmpty(), "Expected immediate tracking when pending.tracked was cleared")

        // Continuing the S1 stream must promote pending to master and finish as Complete
        var lastSnap: ContinuousScanSnapshot = resumeSnap
        for (k in 1 until frames.size) {
            lastSnap = mapper.processFrame(12 + k, frames[k].detections)
        }
        val complete = assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, lastSnap)
        assertEquals(6, complete.mappedFacesCount)
        assertEquals(ContinuousScanCue.AllVerified, complete.cue)

        // Empty frame after completion must still report Complete with AllVerified (never BringIntoView)
        val afterEmpty = mapper.processFrame(999, emptyList())
        val stillComplete = assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, afterEmpty)
        assertEquals(ContinuousScanCue.AllVerified, stillComplete.cue)
    }

    @Test
    fun `minimal-history layer twist freezes logAwb and observations across streak frames`() {
        val mapper = ContinuousFaceletMapper()
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val green = RgbColor(34.0, 168.0, 72.0)
        val red = RgbColor(204.0, 38.0, 42.0)
        val clean = QuadObservation(quad, List(9) { green }, List(9) { 0.9 }, sharpness = 0.95, hasGlare = false)

        // Exactly 6 frames -> 5 observations after master init (meets h.count >= 5 threshold)
        repeat(7) { f -> mapper.processFrame(f, listOf(clean)) }
        val awbBeforeTwist = mapper.masterLogAwb.toList()

        // Twist top outer row to saturated RED with slightly warmer shift across all cells
        val warmGreen = RgbColor(40.0, 168.0, 62.0)
        val twisted =
            clean.copy(
                cellRgbs = List(9) { i -> if (i in 0..2) red else warmGreen },
            )
        var lastSnap: ContinuousScanSnapshot = mapper.processFrame(7, listOf(twisted))
        for (f in 8..11) {
            lastSnap = mapper.processFrame(f, listOf(twisted))
        }
        val twistSnap = assertInstanceOf(ContinuousScanSnapshot.TwistDetected::class.java, lastSnap)
        assertEquals(ContinuousScanCue.LayerTwistDetected, twistSnap.cue)
        assertEquals(awbBeforeTwist, mapper.masterLogAwb.toList(), "Twisted streak frames must not mutate masterLogAwb")
    }

    @Test
    fun `regrip merge under AWB drift keeps masterLogAwb bounded zero-mean and exports consistent calibration`() {
        val cfg =
            SyntheticCubeStream.ScenarioConfig(
                name = "awb_plus_regrip",
                state = SyntheticCubeStream.scramble("R U R' F' L2 D B' U2 R' F L D'"),
                awbDrift = true,
                regripRange = 52..65,
                jitterPx = 0.0,
                nFrames = 140,
            )
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val mapper = ContinuousFaceletMapper()
        var lastSnap: ContinuousScanSnapshot? = null
        for (fr in frames) {
            lastSnap = mapper.processFrame(fr.frameIndex, fr.detections)
        }
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, lastSnap)
        assertTrue(mapper.reappearanceMergeCount >= 1)
        val awb = mapper.masterLogAwb
        assertTrue(awb.all { it in -0.36..0.36 })
        assertTrue(abs(awb.average()) < 1e-6)
        val exported = assertInstanceOf(ContinuousScanExport.Complete::class.java, mapper.export())
        assertEquals(6, requireNotNull(exported.handoff.calibration).centers.size)
    }
}
