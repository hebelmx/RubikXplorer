package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GH #405: [ContinuousFaceletMapper.unreadSlots] classifies every unread non-centre slot, read-only. */
class UnreadSlotDiagnosticsTest {
    /**
     * Feeds the SOLVED synthetic stream through [mapper], letting [mutate] edit each detection's cells in the canonical
     * face's aligned order (face, how many times that face was seen, rgbs, qualities); returns the last snapshot and
     * the mapper's unread slots after it.
     */
    private fun scan(
        mapper: ContinuousFaceletMapper = ContinuousFaceletMapper(),
        cfg: SyntheticCubeStream.ScenarioConfig =
            SyntheticCubeStream.ScenarioConfig(name = "unread", state = CubeState.SOLVED, jitterPx = 0.0, nFrames = 120),
        onFrame: (ContinuousScanSnapshot, ContinuousFaceletMapper) -> Unit = { _, _ -> },
        mutate: (Face, Int, MutableList<RgbColor>, MutableList<Double>) -> Unit,
    ): Pair<ContinuousScanSnapshot, List<UnreadSlot>> {
        val frames = SyntheticCubeStream.generateSequence(cfg)
        val seen = HashMap<Face, Int>()
        var snap: ContinuousScanSnapshot? = null
        for (fr in frames) {
            val dets =
                fr.detections.map { det ->
                    val match =
                        fr.visibleFaces.entries.minByOrNull { (_, q) ->
                            (0 until 4).minOf { s ->
                                val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                                (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], q.corners[k]) }
                            }
                        } ?: return@map det
                    val shift =
                        (0 until 4).minBy { s ->
                            val rolled = ContinuousColorModel.rollQuad(det.quad, s)
                            (0 until 4).sumOf { k -> ProjectiveGeometry.distance(rolled.corners[k], match.value.corners[k]) }
                        }
                    val rgbs = ContinuousColorModel.rotateGridCcw(det.cellRgbs, shift).toMutableList()
                    val quals = ContinuousColorModel.rotateGridCcw(det.cellQualities, shift).toMutableList()
                    val n = (seen[match.key] ?: 0) + 1
                    seen[match.key] = n
                    mutate(match.key, n, rgbs, quals)
                    det.copy(
                        cellRgbs = ContinuousColorModel.rotateGridCw(rgbs, shift),
                        cellQualities = ContinuousColorModel.rotateGridCw(quals, shift),
                    )
                }
            snap = mapper.processFrame(fr.frameIndex, dets).also { onFrame(it, mapper) }
        }
        return requireNotNull(snap) to mapper.unreadSlots()
    }

    private val blank: (Face, Int, MutableList<RgbColor>, MutableList<Double>) -> Unit = { _, _, _, _ -> }

    /** Every listed slot is a non-centre slot with no colour, listed once, in slot order. */
    private fun assertOnlyUnread(
        snap: ContinuousScanSnapshot,
        unread: List<UnreadSlot>,
    ) {
        val slots = unread.map { it.slot }
        assertEquals(slots.sorted(), slots)
        assertEquals(slots.toSet().size, slots.size)
        for (s in slots) {
            assertNotEquals(4, s.cellIndex, "centre listed: $s")
            assertNull(snap.net.getValue(s.face)[s.cellIndex], "read slot listed: $s")
        }
        val blanks = Face.entries.sumOf { f -> (0 until 9).count { it != 4 && snap.net.getValue(f)[it] == null } }
        assertEquals(blanks, unread.size, "every blank non-centre slot is listed")
    }

    @Test
    fun `a clean complete scan lists no unread slot`() {
        val (snap, unread) = scan(mutate = blank)
        assertInstanceOf(ContinuousScanSnapshot.Complete::class.java, snap)
        assertTrue(unread.isEmpty(), unread.toString())
    }

    @Test
    fun `a cell with only low-quality evidence is not ready with its numbers`() {
        val (snap, unread) = scan { f, _, _, q -> if (f == Face.F) q[1] = 0.01 }
        assertOnlyUnread(snap, unread)
        val notReady = assertInstanceOf(UnreadSlot.NotReady::class.java, unread.single { it.slot == FaceSlot(Face.F, 1) })
        assertTrue(
            notReady.qualitySum < UnreadSlot.MIN_READY_QUALITY_SUM || notReady.count < UnreadSlot.MIN_READY_COUNT,
            notReady.toString(),
        )
        // The recorded numbers are the cell's own robust reading, not the arrays' zero defaults.
        assertTrue(notReady.count >= 1 && notReady.qualitySum > 0.0, notReady.toString())
    }

    @Test
    fun `a cell between two prototypes with fewer than 48 ready cells is below the margin`() {
        val (snap, unread) =
            scan { f, _, r, q ->
                if (f == Face.F) {
                    q[1] = 0.01
                    r[3] = RgbColor(221.0, 78.0, 35.0) // between red and orange
                }
            }
        assertOnlyUnread(snap, unread)
        assertInstanceOf(UnreadSlot.NotReady::class.java, unread.single { it.slot == FaceSlot(Face.F, 1) })
        val below = assertInstanceOf(UnreadSlot.BelowMargin::class.java, unread.single { it.slot == FaceSlot(Face.F, 3) })
        assertTrue(below.bestD >= UnreadSlot.MAX_PER_CELL_DISTANCE || below.margin < UnreadSlot.MIN_PER_CELL_MARGIN, below.toString())
        // The recorded numbers are the solve's own distances, not the arrays' zero defaults.
        assertTrue(below.bestD > 0.0 && below.margin > 0.0, below.toString())
    }

    @Test
    fun `a far cell in the balanced 48-cell solve is above the distance`() {
        val (snap, unread) = scan { f, _, r, _ -> if (f == Face.F) r[3] = RgbColor(128.0, 128.0, 128.0) }
        assertOnlyUnread(snap, unread)
        val above = assertInstanceOf(UnreadSlot.AboveDistance::class.java, unread.single())
        assertEquals(FaceSlot(Face.F, 3), above.slot)
        assertTrue(above.chosenD >= UnreadSlot.MAX_BALANCED_DISTANCE, above.toString())
    }

    @Test
    fun `a cell without a robust reading is not observed`() {
        // Two strong but contradictory readings (red, then blue) make the cell's robust value undefined.
        val (snap, unread) =
            scan { f, n, r, q ->
                if (f == Face.F) {
                    if (n <= 2) {
                        q[1] = 0.9
                        r[1] = if (n == 1) RgbColor(204.0, 38.0, 42.0) else RgbColor(28.0, 86.0, 206.0)
                    } else {
                        q[1] = 0.01
                    }
                }
            }
        assertOnlyUnread(snap, unread)
        assertEquals(listOf<UnreadSlot>(UnreadSlot.NotObserved(FaceSlot(Face.F, 1))), unread)
    }

    @Test
    fun `a slot cleared by the local-invariant loop is reopened`() {
        // The conflict pattern of ContinuousFaceletMapperTest: F[1] reads YELLOW and D[1] GREEN, F[1] weaker.
        val yellow = RgbColor(235.0, 212.0, 32.0)
        val green = RgbColor(34.0, 168.0, 72.0)
        var reopened: List<UnreadSlot>? = null
        scan(
            mutate = { f, n, r, q ->
                if (f == Face.F) {
                    r[1] = yellow
                    q[1] = if (n <= 4) 0.16 else 0.01
                } else if (f == Face.D) {
                    r[1] = green
                    q[1] = if (n <= 4) 0.18 else 0.01
                }
            },
            onFrame = { snap, mapper ->
                if (reopened == null && FaceSlot(Face.F, 1) in snap.conflictedSlots) {
                    reopened = mapper.unreadSlots()
                    assertOnlyUnread(snap, reopened)
                }
            },
        )
        val atConflict = requireNotNull(reopened) { "expected F[1] to be reopened" }
        assertTrue(UnreadSlot.Reopened(FaceSlot(Face.F, 1)) in atConflict, atConflict.toString())
    }

    @Test
    fun `an unanchored, partial or reset mapper lists nothing`() {
        val mapper = ContinuousFaceletMapper()
        assertTrue(mapper.unreadSlots().isEmpty())
        mapper.processFrame(0, emptyList())
        assertTrue(mapper.unreadSlots().isEmpty())

        // One face only: no global rotation.
        val quad = Quad2D(listOf(Point2D(200.0, 150.0), Point2D(320.0, 150.0), Point2D(320.0, 270.0), Point2D(200.0, 270.0)))
        val obs = QuadObservation(quad, List(9) { RgbColor(40.0, 175.0, 75.0) }, List(9) { 0.9 }, sharpness = 0.9, hasGlare = false)
        for (i in 1..5) mapper.processFrame(i, listOf(obs))
        assertFalse(mapper.processFrame(6, listOf(obs)).globalAnchored)
        assertTrue(mapper.unreadSlots().isEmpty())

        // An anchored scan with unread slots, then reset.
        val anchored = ContinuousFaceletMapper()
        scan(mapper = anchored, mutate = { f, _, _, q -> if (f == Face.F) q[1] = 0.01 })
        assertTrue(anchored.unreadSlots().isNotEmpty())
        anchored.reset()
        assertTrue(anchored.unreadSlots().isEmpty())
    }

    @Test
    fun `a layer twist after an anchored scan with unread slots lists nothing`() {
        val cfg =
            SyntheticCubeStream.ScenarioConfig(
                name = "unread_twist",
                state = CubeState.SOLVED,
                jitterPx = 0.0,
                nFrames = 160,
                twistAtFrame = 140,
            )
        var unreadBeforeTwist = false
        val (snap, unread) =
            scan(
                cfg = cfg,
                onFrame = { s, m -> if (!s.twistDetected && m.unreadSlots().isNotEmpty()) unreadBeforeTwist = true },
                mutate = { f, _, _, q -> if (f == Face.F) q[1] = 0.01 },
            )
        assertTrue(unreadBeforeTwist, "expected unread slots before the twist")
        assertTrue(snap.twistDetected, "expected the twist to be detected")
        assertTrue(unread.isEmpty(), unread.toString())
    }

    @Test
    fun `reading the diagnostics leaves snapshots and exports unchanged`() {
        val plain = ContinuousFaceletMapper()
        val probed = ContinuousFaceletMapper()
        val mutate: (Face, Int, MutableList<RgbColor>, MutableList<Double>) -> Unit = { f, _, _, q -> if (f == Face.F) q[1] = 0.01 }
        val a = scan(mapper = plain, mutate = mutate).first
        val b = scan(mapper = probed, mutate = mutate, onFrame = { _, m -> m.unreadSlots() }).first
        assertEquals(a, b)
        assertEquals(plain.export(), probed.export())
        assertEquals(plain.exportHandoff(), probed.exportHandoff())
    }
}
