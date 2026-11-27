package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.StickerConfidence
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cap on each per-cell observation list in [ContinuousFaceletMapper] (GitHub issue #402). This is a deliberate deviation
 * from `pc_spike/mapper.py`, which appends without bound: unbounded lists make per-frame time and heap grow linearly
 * with scan length on a long or stalled scan. While every list is <= the cap, results are identical to the uncapped
 * mapper; 256 is well above the longest per-cell list measured across the S1-S7 stress, hand-off and golden replay
 * sessions (86), so those stay bit-identical. Beyond the cap it is a deliberate approximation: the retained list is the
 * first K entries of the stable descending-quality order by insertion position (a merge appends pending observations
 * after master's own, whatever their frame index), and the robust value's top-K size, count and quality sum are then
 * derived from that retained list, not the full history. Trade-off: once a list is full, a new observation (including a
 * merged pending one) enters only if its quality is strictly above the retained minimum, so later equal-or-lower-quality
 * evidence is ignored.
 */
internal const val MAX_OBS_PER_CELL = 256

/**
 * Pure-JVM port of `pc_spike/mapper.py`: intrinsics-free 2D shared-edge face graph, historical
 * center-color lock, 24-SO(3) regrip merger with symmetry/outlier guards, zero-mean bounded
 * log-RGB AWB drift, shading-invariant CIELAB + WHITE-referenced hue + exact 8-per-color balanced
 * solver, local cubie invariant reopening, mid-scan layer-twist detection, and canonical
 * `Orientation.HOME` handoff export.
 */
class ContinuousFaceletMapper {
    private data class LabVec(
        val l: Double,
        val a: Double,
        val b: Double,
    ) {
        fun dist(o: LabVec): Double {
            val dl = l - o.l
            val da = a - o.a
            val db = b - o.b
            return sqrt(dl * dl + da * da + db * db)
        }

        operator fun plus(o: LabVec): LabVec = LabVec(l + o.l, a + o.a, b + o.b)

        operator fun minus(o: LabVec): LabVec = LabVec(l - o.l, a - o.a, b - o.b)

        fun toDomainLab(): Lab = Lab(l.coerceIn(0.0, 100.0), a, b)

        companion object {
            fun from(lab: Lab): LabVec = LabVec(lab.l, lab.a, lab.b)
        }
    }

    private data class CellObs(
        val frameIndex: Int,
        val rgbAwb: RgbColor,
        val lab: LabVec,
        val quality: Double,
    )

    private data class RobustLab(
        val lab: LabVec,
        val standardLab: Lab?,
        val qualitySum: Double,
        val count: Int,
    )

    private data class TrackedFace(
        val fragFace: Face,
        val alignedQuad: Quad2D,
        val rawRgbs: List<RgbColor>,
        val quals: List<Double>,
    )

    private data class AssignedFace(
        val fragFace: Face,
        val alignedQuad: Quad2D,
        val shift: Int,
    )

    /** Cumulative count of uncached [CubeFragment.faceRobustLab] computations, shared by every fragment of one mapper. */
    private class RobustRecomputeCounter {
        var count: Long = 0
    }

    private class CubeFragment(
        val fragId: Int,
        private val recomputeCounter: RobustRecomputeCounter,
    ) {
        private val obs: Array<ArrayList<CellObs>> = Array(Face.entries.size * 9) { ArrayList() }
        private val robustCache: Array<RobustLab?> = arrayOfNulls(Face.entries.size * 9)
        private val robustCacheValid: BooleanArray = BooleanArray(Face.entries.size * 9)
        private val robustStdCache: Array<RobustLab?> = arrayOfNulls(Face.entries.size * 9)
        private val robustStdCacheValid: BooleanArray = BooleanArray(Face.entries.size * 9)
        val observedFaces: MutableSet<Face> = LinkedHashSet()
        var tracked: MutableMap<Face, TrackedFace> = LinkedHashMap()
        val logAwb: DoubleArray = DoubleArray(3)
        val twistStreak: IntArray = IntArray(Face.entries.size)
        var frameCount: Int = 0

        /** Read-only view of one cell's observations; every mutation goes through [addObs]. */
        fun obsList(
            face: Face,
            cellIdx: Int,
        ): List<CellObs> = obs[face.ordinal * 9 + cellIdx]

        /** Largest per-cell observation list in this fragment. */
        fun maxObsListSize(): Int = obs.maxOf { it.size }

        /**
         * The only mutation path for a cell's observation list. Keeps at most [MAX_OBS_PER_CELL] entries: when the list
         * is full, the observation that sorts last in the stable descending-quality order is dropped (minimum quality;
         * on ties the latest by insertion position, which may be [o] itself), so the retained membership is the first K
         * entries of that order. Results match the uncapped list only while it is <= the cap; past it this is a
         * deliberate approximation (see [MAX_OBS_PER_CELL]), and a new observation enters a full list only if its
         * quality is strictly above the retained minimum. The cell's robust cache is invalidated only when the list
         * actually changes.
         */
        fun addObs(
            face: Face,
            cellIdx: Int,
            o: CellObs,
        ) {
            val idx = face.ordinal * 9 + cellIdx
            val lst = obs[idx]
            if (lst.size >= MAX_OBS_PER_CELL) {
                var minIdx = 0
                for (i in 1 until lst.size) {
                    // <= keeps the latest index among equal minimum qualities.
                    if (lst[i].quality <= lst[minIdx].quality) minIdx = i
                }
                // The new observation is the latest, so it loses every tie and is the one dropped.
                if (o.quality <= lst[minIdx].quality) return
                lst.removeAt(minIdx)
            }
            lst.add(o)
            robustCacheValid[idx] = false
            robustStdCacheValid[idx] = false
            robustCache[idx] = null
            robustStdCache[idx] = null
        }

        /** Cached robust value of one cell; identical to [computeFaceRobustLab] for the cell's current list. */
        fun faceRobustLab(
            face: Face,
            cellIdx: Int,
            includeStandardLab: Boolean = false,
        ): RobustLab? {
            val idx = face.ordinal * 9 + cellIdx
            val valid = if (includeStandardLab) robustStdCacheValid else robustCacheValid
            val cache = if (includeStandardLab) robustStdCache else robustCache
            if (valid[idx]) return cache[idx]
            val r = computeFaceRobustLab(obs[idx], includeStandardLab = includeStandardLab)
            recomputeCounter.count++
            cache[idx] = r
            valid[idx] = true
            return r
        }

        private fun computeFaceRobustLab(
            lst: List<CellObs>,
            topKFrac: Double = 0.45,
            includeStandardLab: Boolean = false,
        ): RobustLab? {
            if (lst.isEmpty()) return null
            val ordered = lst.sortedByDescending { it.quality }
            val maxQ = ordered[0].quality
            val qFloor = max(0.12, 0.45 * maxQ)
            val clean = ordered.filter { it.quality >= qFloor }.ifEmpty { listOf(ordered[0]) }
            val k = max(min(3, clean.size), ceil(clean.size * topKFrac).toInt()).coerceAtMost(clean.size)
            var top = clean.subList(0, k)
            if (top.size == 2 && top[0].lab.dist(top[1].lab) > 18.0) return null

            if (top.size >= 4) {
                val medL = medianOf(DoubleArray(top.size) { top[it].lab.l })
                val medA = medianOf(DoubleArray(top.size) { top[it].lab.a })
                val medB = medianOf(DoubleArray(top.size) { top[it].lab.b })
                val med = LabVec(medL, medA, medB)
                val ds = DoubleArray(top.size) { top[it].lab.dist(med) }
                val p75 = FaceLatticeFitter.percentile(ds.sortedArray(), 75.0)
                val thresh = max(12.0, p75 * 1.4)
                val inliers = top.filterIndexed { idx, _ -> ds[idx] <= thresh }
                if (inliers.isNotEmpty()) top = inliers
            }

            var wSum = 0.0
            var lSum = 0.0
            var aSum = 0.0
            var bSum = 0.0
            var stdLSum = 0.0
            var stdASum = 0.0
            var stdBSum = 0.0
            for (o in top) {
                wSum += o.quality
                lSum += o.lab.l * o.quality
                aSum += o.lab.a * o.quality
                bSum += o.lab.b * o.quality
                if (includeStandardLab) {
                    val stdLab = ContinuousColorModel.rgbToLab(o.rgbAwb)
                    stdLSum += stdLab.l * o.quality
                    stdASum += stdLab.a * o.quality
                    stdBSum += stdLab.b * o.quality
                }
            }
            val denom = max(1e-6, wSum)
            val est = LabVec(lSum / denom, aSum / denom, bSum / denom)
            val stdEst =
                if (includeStandardLab) {
                    Lab((stdLSum / denom).coerceIn(0.0, 100.0), stdASum / denom, stdBSum / denom)
                } else {
                    null
                }
            val cleanQSum = clean.sumOf { it.quality }
            return RobustLab(est, stdEst, cleanQSum, clean.size)
        }

        fun confirmedFaces(
            minN: Int = 2,
            minQ: Double = 0.40,
        ): Set<Face> {
            val out = LinkedHashSet<Face>()
            for (f in observedFaces) {
                val r = faceRobustLab(f, 4)
                if (r != null && r.count >= minN && r.qualitySum >= minQ) {
                    out.add(f)
                }
            }
            return out
        }
    }

    private var master: CubeFragment? = null
    private var pending: CubeFragment? = null
    private var nextFragId: Int = 0
    private var hasPrevUnattached: Boolean = false
    private var globalRotIdx: Int? = null
    private var centerPrototypes: Map<Color, LabVec>? = null
    private var net: MutableMap<Face, Array<Color?>> = emptyNet()
    private var slotSupport: MutableMap<FaceSlot, Double> = HashMap()
    private var conflictedSlots: MutableSet<FaceSlot> = LinkedHashSet()
    private var twistDetected: Boolean = false
    private var twistFrame: Int? = null
    private var emptyStreak: Int = 0
    private var pendingMissStreak: Int = 0
    private var lastCue: ContinuousScanCue = ContinuousScanCue.BringIntoView
    private val reappearanceMergeCountInternal: MutableList<Int> = mutableListOf()

    private val robustRecomputeCounter = RobustRecomputeCounter()

    /** Test hook: largest per-cell observation list across the live master and pending fragments. */
    internal val maxObsListSize: Int
        get() = max(master?.maxObsListSize() ?: 0, pending?.maxObsListSize() ?: 0)

    /** Test hook: retained (frameIndex, quality) observations of one master cell, in insertion order. */
    internal fun masterCellObservations(
        face: Face,
        cellIdx: Int,
    ): List<Pair<Int, Double>> = master?.obsList(face, cellIdx)?.map { it.frameIndex to it.quality } ?: emptyList()

    /** Test hook: cumulative number of uncached per-cell robust-Lab computations since construction. */
    internal val robustRecomputeCount: Long get() = robustRecomputeCounter.count

    /** Number of regrip reappearance merges/promotions recorded during this session. */
    val reappearanceMergeCount: Int get() = reappearanceMergeCountInternal.size

    /** Current running log-RGB AWB drift of the active master fragment (zero if none). */
    val masterLogAwb: DoubleArray get() = master?.logAwb?.copyOf() ?: DoubleArray(3)

    /** Consecutive frames with no plausible quad so far (read-only, GH #403 tests). */
    val emptyFrameStreak: Int get() = emptyStreak

    /** Reset all session state in place. */
    fun reset() {
        master = null
        pending = null
        nextFragId = 0
        hasPrevUnattached = false
        globalRotIdx = null
        centerPrototypes = null
        net = emptyNet()
        slotSupport = HashMap()
        conflictedSlots = LinkedHashSet()
        twistDetected = false
        twistFrame = null
        emptyStreak = 0
        pendingMissStreak = 0
        lastCue = ContinuousScanCue.BringIntoView
        reappearanceMergeCountInternal.clear()
    }

    /** Process one frame of neutral [QuadObservation] detections and return the updated snapshot. */
    fun processFrame(
        frameIndex: Int,
        detections: List<QuadObservation>,
    ): ContinuousScanSnapshot {
        if (twistDetected) {
            val cue = ContinuousScanCue.LayerTwistDetected
            lastCue = cue
            return buildSnapshot(frameIndex, emptyList(), cue)
        }
        val plausible = filterPlausibleQuads(detections)
        if (plausible.isEmpty()) {
            hasPrevUnattached = false
            emptyStreak++
            if (emptyStreak >= 3) {
                master?.tracked?.clear()
                pending?.tracked?.clear()
            }
            val cue = computeCue(anyGlare = false, trackedNow = emptyList())
            lastCue = cue
            return buildSnapshot(frameIndex, emptyList(), cue)
        }

        emptyStreak = 0
        var anyGlare = false

        val currentMaster = master
        if (currentMaster == null) {
            if (plausible.size >= 2 || hasPrevUnattached) {
                val created = newFragment()
                master = created
                val order = plausible.indices.sortedByDescending { ProjectiveGeometry.quadArea(plausible[it].quad) }
                val assigned = LinkedHashMap<Int, AssignedFace>()
                assigned[order[0]] = AssignedFace(Face.F, plausible[order[0]].quad, shift = 0)
                attachBySharedEdge(created, plausible, assigned, order.drop(1))
                anyGlare = updateFragmentObservations(created, frameIndex, plausible, assigned)
                hasPrevUnattached = false
            } else {
                hasPrevUnattached = true
            }
        } else {
            if (currentMaster.tracked.isNotEmpty()) {
                val (assigned, unmatched) = matchToTracked(currentMaster, plausible)
                if (assigned.isNotEmpty()) {
                    attachBySharedEdge(currentMaster, plausible, assigned, unmatched)
                    anyGlare = updateFragmentObservations(currentMaster, frameIndex, plausible, assigned)
                } else {
                    currentMaster.tracked.clear()
                }
            }

            if (currentMaster.tracked.isEmpty()) {
                val curPending = pending
                if (curPending == null) {
                    val created = newFragment()
                    pending = created
                    pendingMissStreak = 0
                    val order = plausible.indices.sortedByDescending { ProjectiveGeometry.quadArea(plausible[it].quad) }
                    val assigned = LinkedHashMap<Int, AssignedFace>()
                    assigned[order[0]] = AssignedFace(Face.F, plausible[order[0]].quad, shift = 0)
                    attachBySharedEdge(created, plausible, assigned, order.drop(1))
                    anyGlare = updateFragmentObservations(created, frameIndex, plausible, assigned)
                } else {
                    val (assigned, unmatched) = matchToTracked(curPending, plausible)
                    if (assigned.isEmpty()) {
                        pendingMissStreak++
                        val maxMiss = if (curPending.confirmedFaces().isNotEmpty()) 3 else 1
                        if (curPending.tracked.isEmpty() || pendingMissStreak >= maxMiss) {
                            val resetFrag = newFragment()
                            pending = resetFrag
                            pendingMissStreak = 0
                            val order =
                                plausible.indices.sortedByDescending {
                                    ProjectiveGeometry.quadArea(plausible[it].quad)
                                }
                            val freshAssigned = LinkedHashMap<Int, AssignedFace>()
                            freshAssigned[order[0]] = AssignedFace(Face.F, plausible[order[0]].quad, shift = 0)
                            attachBySharedEdge(resetFrag, plausible, freshAssigned, order.drop(1))
                            anyGlare = updateFragmentObservations(resetFrag, frameIndex, plausible, freshAssigned)
                        }
                    } else {
                        pendingMissStreak = 0
                        attachBySharedEdge(curPending, plausible, assigned, unmatched)
                        anyGlare = updateFragmentObservations(curPending, frameIndex, plausible, assigned)
                    }
                }
                tryMergePending(frameIndex)
            }
        }

        solveGlobalColorsAndNet()

        val active =
            pending?.takeIf { it.tracked.isNotEmpty() }
                ?: master
        val visQuads = ArrayList<TrackedQuadOverlay>()
        if (active != null) {
            val rot = globalRotIdx
            for ((fname, tf) in active.tracked) {
                val canonical =
                    if (active === master && rot != null) {
                        ContinuousCubeGeometry.rotateFace(fname, rot)
                    } else {
                        null
                    }
                visQuads.add(TrackedQuadOverlay(face = canonical, localFace = fname, quad = tf.alignedQuad))
            }
        }

        val cue = computeCue(anyGlare, visQuads)
        lastCue = cue
        return buildSnapshot(frameIndex, visQuads, cue)
    }

    /** Export the current scan as a typed domain handoff payload (canonicalized to `Orientation.HOME` when anchored). */
    fun exportHandoff(): ContinuousScanHandoff {
        val m = master ?: return ContinuousScanHandoff(emptyMap(), calibration = null, globalAnchored = false)
        val rot = globalRotIdx
        val confirmed = m.confirmedFaces()
        if (rot == null) {
            val localFaces = LinkedHashMap<Face, ContinuousFaceHandoff>()
            for (mf in confirmed) {
                val labs = List(9) { mi -> m.faceRobustLab(mf, mi, includeStandardLab = true)?.standardLab }
                localFaces[mf] =
                    ContinuousFaceHandoff(
                        face = mf,
                        colors = List(9) { null },
                        confidence = List(9) { StickerConfidence.UNCERTAIN },
                        labs = labs,
                        orientationLocked = false,
                    )
            }
            return ContinuousScanHandoff(faces = localFaces, calibration = null, globalAnchored = false)
        }

        val exportFaces: Set<Face> =
            if (validateCompleteNet() != null) {
                m.observedFaces
            } else {
                confirmed
            }
        val locked = exportFaces.size >= 2
        val canonFaces = LinkedHashMap<Face, ContinuousFaceHandoff>()
        val standardCenters = LinkedHashMap<Color, Lab>()
        for (mf in exportFaces) {
            val cf = ContinuousCubeGeometry.rotateFace(mf, rot)
            val labs = arrayOfNulls<Lab>(9)
            for (mi in 0 until 9) {
                val slot = ContinuousCubeGeometry.rotateSlot(mf, mi, rot)
                labs[slot.cellIndex] = m.faceRobustLab(mf, mi, includeStandardLab = true)?.standardLab
            }
            labs[4]?.takeIf { it.isValid() }?.let { standardCenters[Color.home(cf)] = it }
            val faceColors = net.getValue(cf)
            val colors =
                List(9) { ci ->
                    if (ci == 4) Color.home(cf) else faceColors[ci]
                }
            val confidence =
                List(9) { ci ->
                    if (colors[ci] != null) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN
                }
            canonFaces[cf] =
                ContinuousFaceHandoff(
                    face = cf,
                    colors = colors,
                    confidence = confidence,
                    labs = labs.toList(),
                    orientationLocked = locked,
                )
        }

        val calibration =
            standardCenters
                .takeIf { it.size == Color.entries.size }
                ?.let { centers ->
                    CalibrationCenters(Color.entries.associateWith { c -> centers.getValue(c) })
                }

        val orderedFaces = Face.entries.mapNotNull { f -> canonFaces[f]?.let { f to it } }.toMap()
        return ContinuousScanHandoff(faces = orderedFaces, calibration = calibration, globalAnchored = true)
    }

    /** Export the current scan result (`Complete`, `Partial`, or `TwistDetected`), validating via [CubeValidator]. */
    fun export(): ContinuousScanExport {
        val handoff = exportHandoff()
        if (twistDetected) {
            return ContinuousScanExport.TwistDetected(twistFrame = twistFrame, handoff = handoff)
        }
        val validated = validateCompleteNet()
        if (validated != null && handoff.isComplete) {
            val homeState = validated.reoriented(Orientation.HOME)
            return ContinuousScanExport.Complete(
                state = homeState,
                facelets = homeState.toFacelets(),
                handoff = handoff,
            )
        }
        return ContinuousScanExport.Partial(handoff = handoff)
    }

    private fun validateCompleteNet(): dev.cubentia.domain.cube.CubeState? {
        if (globalRotIdx == null || twistDetected) return null
        val all = ArrayList<Color>(Facelets.SIZE)
        for (f in Face.entries) {
            val cells = net.getValue(f)
            for (i in 0 until 9) {
                val c = cells[i] ?: return null
                all.add(c)
            }
        }
        return (CubeValidator.validate(Facelets(all)) as? ValidationResult.Valid)?.state
    }

    private fun newFragment(): CubeFragment = CubeFragment(nextFragId++, robustRecomputeCounter)

    private fun filterPlausibleQuads(detections: List<QuadObservation>): List<QuadObservation> {
        if (detections.isEmpty()) return emptyList()
        val finite =
            detections.filter { det ->
                det.quad.isFinite() &&
                    ProjectiveGeometry.isConvexQuad(det.quad) &&
                    ProjectiveGeometry.signedArea2(det.quad.corners) > 0.0 &&
                    det.sharpness.isFinite() &&
                    det.cellRgbs.all { it.isFinite() } &&
                    det.cellQualities.all { it.isFinite() }
            }
        if (finite.isEmpty()) return emptyList()
        val areas = DoubleArray(finite.size) { ProjectiveGeometry.quadArea(finite[it].quad) }
        var maxA = 0.0
        for (a in areas) if (a.isFinite() && a > maxA) maxA = a
        val out = ArrayList<QuadObservation>(finite.size)
        for (i in finite.indices) {
            val a = areas[i]
            if (!a.isFinite() || a < 2800.0 || a < 0.28 * maxA) continue
            val (qAng, _) = ProjectiveGeometry.quadQuality(finite[i].quad)
            if (!qAng.isFinite() || qAng <= 0.0) continue
            out.add(finite[i])
        }
        return out
    }

    private data class TrackCandidate(
        val dist: Double,
        val detIndex: Int,
        val face: Face,
        val alignedQuad: Quad2D,
        val shift: Int,
    )

    private fun matchToTracked(
        frag: CubeFragment,
        detections: List<QuadObservation>,
    ): Pair<MutableMap<Int, AssignedFace>, MutableList<Int>> {
        val matched = LinkedHashMap<Int, AssignedFace>()
        val usedFaces = HashSet<Face>()
        val candidates = ArrayList<TrackCandidate>()
        val centerCache = HashMap<Int, Pair<LabVec, Double>>()

        fun detCenter(di: Int): Pair<LabVec, Double> =
            centerCache.getOrPut(di) {
                val obs = detections[di]
                LabVec.from(ContinuousColorModel.shadingInvariantLab(obs.cellRgbs[4])) to
                    obs.cellQualities[4].coerceAtLeast(0.01)
            }

        val awbScaleR = exp(-frag.logAwb[0])
        val awbScaleG = exp(-frag.logAwb[1])
        val awbScaleB = exp(-frag.logAwb[2])

        for (di in detections.indices) {
            val det = detections[di]
            for ((fname, tf) in frag.tracked) {
                val edgeLen = ProjectiveGeometry.quadQuality(tf.alignedQuad).second
                val maxDisp = 0.42 * edgeLen
                var bestShift = 0
                var bestD = 1e9
                for (shift in 0 until 4) {
                    val cand = ContinuousColorModel.rollQuad(det.quad, shift)
                    var dSum = 0.0
                    for (k in 0 until 4) {
                        dSum += ProjectiveGeometry.distance(cand.corners[k], tf.alignedQuad.corners[k])
                    }
                    val d = dSum / 4.0
                    if (d < bestD) {
                        bestD = d
                        bestShift = shift
                    }
                }
                if (bestD < maxDisp) {
                    val aligned = ContinuousColorModel.rollQuad(det.quad, bestShift)
                    val (curLab, curQ) = detCenter(di)
                    val prevLab = LabVec.from(ContinuousColorModel.shadingInvariantLab(tf.rawRgbs[4]))
                    val centerDe = curLab.dist(prevLab)
                    if (curQ > 0.20 && tf.quals[4] > 0.20 && centerDe > 24.0) continue

                    val histC = frag.faceRobustLab(fname, 4)
                    if (curQ > 0.20 && histC != null && histC.count >= 2 && histC.qualitySum >= 0.40) {
                        val cRgb = det.cellRgbs[4]
                        val awbRgb =
                            RgbColor(
                                (cRgb.r * awbScaleR).coerceIn(0.0, 255.0),
                                (cRgb.g * awbScaleG).coerceIn(0.0, 255.0),
                                (cRgb.b * awbScaleB).coerceIn(0.0, 255.0),
                            )
                        val curAwbLab = LabVec.from(ContinuousColorModel.shadingInvariantLab(awbRgb))
                        if (curAwbLab.dist(histC.lab) > 26.0) continue
                    }
                    candidates.add(TrackCandidate(bestD, di, fname, aligned, bestShift))
                }
            }
        }

        candidates.sortBy { it.dist }
        for (c in candidates) {
            if (c.detIndex in matched || c.face in usedFaces) continue
            matched[c.detIndex] = AssignedFace(c.face, c.alignedQuad, c.shift)
            usedFaces.add(c.face)
        }
        val unmatched = detections.indices.filterTo(ArrayList()) { it !in matched }
        return matched to unmatched
    }

    private fun attachBySharedEdge(
        frag: CubeFragment,
        detections: List<QuadObservation>,
        assigned: MutableMap<Int, AssignedFace>,
        unmatched: List<Int>,
    ) {
        val remaining = unmatched.toMutableList()
        val centerCache = HashMap<Int, Pair<LabVec, Double>>()

        fun centerOf(di: Int): Pair<LabVec, Double> =
            centerCache.getOrPut(di) {
                val obs = detections[di]
                LabVec.from(ContinuousColorModel.shadingInvariantLab(obs.cellRgbs[4])) to
                    obs.cellQualities[4].coerceAtLeast(0.01)
            }

        val assignedCenters = assigned.keys.mapTo(ArrayList()) { centerOf(it) }
        val confirmed = frag.confirmedFaces()
        val awbScaleR = exp(-frag.logAwb[0])
        val awbScaleG = exp(-frag.logAwb[1])
        val awbScaleB = exp(-frag.logAwb[2])

        var changed = true
        while (changed && remaining.isNotEmpty()) {
            changed = false
            val usedFaces = assigned.values.mapTo(HashSet()) { it.fragFace }
            val remSnapshot = remaining.toList()
            for (di in remSnapshot) {
                val detQ = detections[di].quad
                var attached = false
                val assignedSnapshot = assigned.values.toList()
                for (af in assignedSnapshot) {
                    val edge = ProjectiveGeometry.sharedEdge(af.alignedQuad, detQ) ?: continue
                    val (ea, ebRaw) = edge
                    val (fb, ebCanon) = ContinuousCubeGeometry.FACE_ADJACENCY.getValue(af.fragFace to ea)
                    if (fb in usedFaces) continue
                    val rotB = Math.floorMod(ebRaw - ebCanon, 4)
                    val qb = ContinuousColorModel.rollQuad(detQ, rotB)
                    val (cLab, cQ) = centerOf(di)

                    // Duplicate quad guard on the same frame
                    if (cQ > 0.25 && assignedCenters.any { (aLab, aQ) -> aQ > 0.25 && cLab.dist(aLab) < 11.0 }) {
                        continue
                    }
                    // Contradiction guard against already-confirmed center in this fragment (AWB-compensated)
                    if (fb in confirmed && cQ > 0.25) {
                        val hist = frag.faceRobustLab(fb, 4)
                        if (hist != null) {
                            val cRgb = detections[di].cellRgbs[4]
                            val awbRgb =
                                RgbColor(
                                    (cRgb.r * awbScaleR).coerceIn(0.0, 255.0),
                                    (cRgb.g * awbScaleG).coerceIn(0.0, 255.0),
                                    (cRgb.b * awbScaleB).coerceIn(0.0, 255.0),
                                )
                            val curAwbLab = LabVec.from(ContinuousColorModel.shadingInvariantLab(awbRgb))
                            if (curAwbLab.dist(hist.lab) > 26.0) continue
                        }
                    }

                    assigned[di] = AssignedFace(fb, qb, rotB)
                    assignedCenters.add(cLab to cQ)
                    usedFaces.add(fb)
                    remaining.remove(di)
                    changed = true
                    attached = true
                    break
                }
                if (attached) break
            }
        }
    }

    private data class SampledFace(
        val alignedQuad: Quad2D,
        val rgbs: List<RgbColor>,
        val quals: List<Double>,
    )

    private fun updateFragmentObservations(
        frag: CubeFragment,
        frameIndex: Int,
        detections: List<QuadObservation>,
        assigned: Map<Int, AssignedFace>,
    ): Boolean {
        var anyGlare = false
        val sampled = LinkedHashMap<Face, SampledFace>()
        for ((di, af) in assigned) {
            val obs = detections[di]
            anyGlare = anyGlare || obs.hasGlare
            val rgbs = ContinuousColorModel.rotateGridCcw(obs.cellRgbs, af.shift)
            val quals =
                ContinuousColorModel.rotateGridCcw(obs.cellQualities, af.shift).map { it.coerceAtLeast(0.01) }
            sampled[af.fragFace] = SampledFace(af.alignedQuad, rgbs, quals)
        }

        // 1. Incremental zero-mean bounded log-RGB chromaticity AWB drift
        val chromaR = ArrayList<Double>()
        val chromaG = ArrayList<Double>()
        val chromaB = ArrayList<Double>()
        for ((fname, sf) in sampled) {
            val prev = frag.tracked[fname] ?: continue
            val dR = ArrayList<Double>(9)
            val dG = ArrayList<Double>(9)
            val dB = ArrayList<Double>(9)
            for (ci in 0 until 9) {
                if (sf.quals[ci] > 0.50 && prev.quals[ci] > 0.50) {
                    val c = sf.rgbs[ci]
                    val p = prev.rawRgbs[ci]
                    val dr = ln(c.r.coerceIn(4.0, 255.0)) - ln(p.r.coerceIn(4.0, 255.0))
                    val dg = ln(c.g.coerceIn(4.0, 255.0)) - ln(p.g.coerceIn(4.0, 255.0))
                    val db = ln(c.b.coerceIn(4.0, 255.0)) - ln(p.b.coerceIn(4.0, 255.0))
                    val mean = (dr + dg + db) / 3.0
                    dR.add(dr - mean)
                    dG.add(dg - mean)
                    dB.add(db - mean)
                }
            }
            if (dR.size >= 5) {
                chromaR.add(medianOf(dR.toDoubleArray()))
                chromaG.add(medianOf(dG.toDoubleArray()))
                chromaB.add(medianOf(dB.toDoubleArray()))
            }
        }
        val nextLogAwb = frag.logAwb.copyOf()
        if (chromaR.isNotEmpty()) {
            val stepR = medianOf(chromaR.toDoubleArray()).coerceIn(-0.025, 0.025)
            val stepG = medianOf(chromaG.toDoubleArray()).coerceIn(-0.025, 0.025)
            val stepB = medianOf(chromaB.toDoubleArray()).coerceIn(-0.025, 0.025)
            nextLogAwb[0] += stepR
            nextLogAwb[1] += stepG
            nextLogAwb[2] += stepB
            normalizeLogAwbInPlace(nextLogAwb)
        }

        val awbScaleR = exp(-nextLogAwb[0])
        val awbScaleG = exp(-nextLogAwb[1])
        val awbScaleB = exp(-nextLogAwb[2])

        // 2. Nonrigid layer-twist check across ALL visible faces BEFORE mutating AWB or appending observations
        val pendingObs = LinkedHashMap<Face, Triple<List<RgbColor>, List<LabVec>, List<Double>>>(sampled.size)
        val lineTwistedFaces = HashSet<Face>()
        for ((fname, sf) in sampled) {
            val rgbsAwb =
                List(9) { ci ->
                    val raw = sf.rgbs[ci]
                    RgbColor(
                        (raw.r * awbScaleR).coerceIn(0.0, 255.0),
                        (raw.g * awbScaleG).coerceIn(0.0, 255.0),
                        (raw.b * awbScaleB).coerceIn(0.0, 255.0),
                    )
                }
            val labs = List(9) { ci -> LabVec.from(ContinuousColorModel.shadingInvariantLab(rgbsAwb[ci])) }
            pendingObs[fname] = Triple(rgbsAwb, labs, sf.quals)

            val centerHist = frag.faceRobustLab(fname, 4)
            if (
                frag === master &&
                !anyGlare &&
                centerHist != null &&
                centerHist.count >= 5 &&
                sf.quals[4] > 0.55
            ) {
                val centerDe = labs[4].dist(centerHist.lab)
                if (centerDe < 12.0) {
                    val flippedCells = HashSet<Int>()
                    var stableCells = 0
                    val faceBrights =
                        DoubleArray(9) { k ->
                            (sf.rgbs[k].r + sf.rgbs[k].g + sf.rgbs[k].b) / 3.0
                        }
                    val faceMedBright = medianOf(faceBrights)
                    for (ci in 0 until 9) {
                        if (ci == 4 || sf.quals[ci] < 0.60) continue
                        val h = frag.faceRobustLab(fname, ci)
                        if (h != null && h.count >= 5 && h.qualitySum >= 2.2) {
                            val deAll = labs[ci].dist(h.lab)
                            if (deAll < 14.0) {
                                stableCells++
                            } else if (deAll > 33.0) {
                                val chromaCur = hypot(labs[ci].a, labs[ci].b)
                                val chromaHist = hypot(h.lab.a, h.lab.b)
                                val rawBright = faceBrights[ci]
                                val isGlareWashout =
                                    (chromaCur < chromaHist - 10.0) && (rawBright > faceMedBright + 18.0)
                                if (!isGlareWashout) {
                                    flippedCells.add(ci)
                                }
                            }
                        }
                    }
                    val lineTwist =
                        flippedCells.size >= 2 &&
                            stableCells >= 4 &&
                            OUTER_LINES.any { line -> flippedCells.all { it in line } }
                    if (lineTwist) {
                        lineTwistedFaces.add(fname)
                        frag.twistStreak[fname.ordinal]++
                        if (frag.twistStreak[fname.ordinal] >= 5) {
                            twistDetected = true
                            if (twistFrame == null) twistFrame = frameIndex
                        }
                    } else {
                        frag.twistStreak[fname.ordinal] = 0
                    }
                }
            }
        }

        if (!twistDetected) {
            if (lineTwistedFaces.isEmpty()) {
                nextLogAwb.copyInto(frag.logAwb)
            }
            for ((fname, triple) in pendingObs) {
                if (fname in lineTwistedFaces) continue
                val (rgbsAwb, labs, quals) = triple
                for (ci in 0 until 9) {
                    frag.addObs(
                        fname,
                        ci,
                        CellObs(
                            frameIndex = frameIndex,
                            rgbAwb = rgbsAwb[ci],
                            lab = labs[ci],
                            quality = quals[ci],
                        ),
                    )
                }
                frag.observedFaces.add(fname)
            }
            val newTracked = LinkedHashMap<Face, TrackedFace>()
            for ((fname, sf) in sampled) {
                newTracked[fname] = TrackedFace(fname, sf.alignedQuad, sf.rgbs, sf.quals)
            }
            frag.tracked = newTracked
            frag.frameCount++
        }
        return anyGlare
    }

    private data class MergeRotScore(
        val meanErr: Double,
        val overlapFaces: Int,
        val rotIdx: Int,
        val shift: LabVec,
        val maxCenterErr: Double,
        val severeMismatches: Int,
    )

    private fun tryMergePending(frameIndex: Int) {
        val m = master ?: return
        val p = pending ?: return
        val pConf = p.confirmedFaces()
        val mConf = m.confirmedFaces()
        if (pConf.isEmpty()) return

        if (globalRotIdx == null && mConf.size < 3 && pConf.size > mConf.size) {
            promotePendingToMaster(p)
            return
        }

        val pSlots = LinkedHashMap<FaceSlot, Pair<LabVec, Double>>()
        for (f in pConf) {
            for (i in 0 until 9) {
                val r = p.faceRobustLab(f, i)
                if (r != null && r.qualitySum >= 0.35) {
                    pSlots[FaceSlot(f, i)] = r.lab to r.qualitySum
                }
            }
        }
        val mSlots = LinkedHashMap<FaceSlot, Pair<LabVec, Double>>()
        for (f in mConf) {
            for (i in 0 until 9) {
                val r = m.faceRobustLab(f, i)
                if (r != null && r.qualitySum >= 0.35) {
                    mSlots[FaceSlot(f, i)] = r.lab to r.qualitySum
                }
            }
        }
        if (pSlots.isEmpty() || mSlots.isEmpty()) return

        val rotScores = ArrayList<MergeRotScore>(ContinuousCubeGeometry.SO3_COUNT)
        for (rotIdx in 0 until ContinuousCubeGeometry.SO3_COUNT) {
            val pairsP = ArrayList<LabVec>()
            val pairsM = ArrayList<LabVec>()
            val weights = ArrayList<Double>()
            val isCenter = ArrayList<Boolean>()
            val overlapFaces = HashSet<Face>()

            for ((pSlot, pVal) in pSlots) {
                val mSlot = ContinuousCubeGeometry.rotateSlot(pSlot.face, pSlot.cellIndex, rotIdx)
                val mVal = mSlots[mSlot] ?: continue
                pairsP.add(pVal.first)
                pairsM.add(mVal.first)
                weights.add(min(pVal.second, mVal.second) * if (pSlot.cellIndex == 4) 2.5 else 1.0)
                isCenter.add(pSlot.cellIndex == 4)
                overlapFaces.add(mSlot.face)
            }
            if (overlapFaces.isEmpty()) continue

            val count = pairsP.size
            val diffL = DoubleArray(count) { pairsM[it].l - pairsP[it].l }
            val diffA = DoubleArray(count) { pairsM[it].a - pairsP[it].a }
            val diffB = DoubleArray(count) { pairsM[it].b - pairsP[it].b }
            val shift =
                LabVec(
                    medianOf(diffL).coerceIn(-12.0, 12.0),
                    medianOf(diffA).coerceIn(-14.0, 14.0),
                    medianOf(diffB).coerceIn(-14.0, 14.0),
                )

            var errWeightSum = 0.0
            var weightSum = 0.0
            var maxCenterErr = 0.0
            var severeMismatches = 0
            for (i in 0 until count) {
                val e = (pairsP[i] + shift).dist(pairsM[i])
                val w = weights[i]
                errWeightSum += e * w
                weightSum += w
                if (isCenter[i]) {
                    if (e > maxCenterErr) maxCenterErr = e
                } else if (w >= 0.8 && e > 35.0) {
                    severeMismatches++
                }
            }
            val meanErr = errWeightSum / max(1e-6, weightSum)
            rotScores.add(MergeRotScore(meanErr, overlapFaces.size, rotIdx, shift, maxCenterErr, severeMismatches))
        }

        if (rotScores.isEmpty()) return
        rotScores.sortBy { it.meanErr }
        val best = rotScores[0]
        val secondErr = if (rotScores.size > 1) rotScores[1].meanErr else 999.0
        val margin = secondErr - best.meanErr
        val maxAllowedErr = if (best.overlapFaces == 1) 14.5 else 16.5

        val canMerge =
            !(
                (pConf.size < 2 && margin < 12.0) ||
                    best.meanErr > maxAllowedErr ||
                    margin < 6.0 ||
                    best.maxCenterErr > 26.0 ||
                    best.severeMismatches >= 2
            )
        if (!canMerge) {
            if (
                (mConf.size < 6 && pConf.size == 6) ||
                (globalRotIdx == null && mConf.size < 5 && pConf.size >= mConf.size + 2)
            ) {
                promotePendingToMaster(p)
                reappearanceMergeCountInternal.add(frameIndex)
            }
            return
        }

        val mergeAwbScaleR = exp(-m.logAwb[0])
        val mergeAwbScaleG = exp(-m.logAwb[1])
        val mergeAwbScaleB = exp(-m.logAwb[2])
        val mergeFaces = HashSet<Face>(pConf)
        for (pf in p.tracked.keys) {
            if (p.obsList(pf, 4).size >= 2) mergeFaces.add(pf)
        }
        for (pf in mergeFaces) {
            for (pi in 0 until 9) {
                val obsList = p.obsList(pf, pi)
                if (obsList.isEmpty()) continue
                val mSlot = ContinuousCubeGeometry.rotateSlot(pf, pi, best.rotIdx)
                for (o in obsList) {
                    val alignedRgbAwb =
                        RgbColor(
                            (o.rgbAwb.r * mergeAwbScaleR).coerceIn(0.0, 255.0),
                            (o.rgbAwb.g * mergeAwbScaleG).coerceIn(0.0, 255.0),
                            (o.rgbAwb.b * mergeAwbScaleB).coerceIn(0.0, 255.0),
                        )
                    m.addObs(mSlot.face, mSlot.cellIndex, CellObs(o.frameIndex, alignedRgbAwb, o.lab + best.shift, o.quality))
                }
                m.observedFaces.add(mSlot.face)
            }
        }
        for (c in 0 until 3) {
            m.logAwb[c] += p.logAwb[c]
        }
        normalizeLogAwbInPlace(m.logAwb)

        val newTracked = LinkedHashMap<Face, TrackedFace>()
        for ((pf, tf) in p.tracked) {
            val mf = ContinuousCubeGeometry.rotateFace(pf, best.rotIdx)
            val mTlIdx = ContinuousCubeGeometry.rotateSlot(pf, 0, best.rotIdx).cellIndex
            val cwRot = CORNER_ORDER.indexOf(mTlIdx)
            val alignedM = ContinuousColorModel.rollQuad(tf.alignedQuad, -cwRot)
            newTracked[mf] =
                TrackedFace(
                    fragFace = mf,
                    alignedQuad = alignedM,
                    rawRgbs = ContinuousColorModel.rotateGridCw(tf.rawRgbs, cwRot),
                    quals = ContinuousColorModel.rotateGridCw(tf.quals, cwRot),
                )
        }
        m.tracked = newTracked
        pending = null
        reappearanceMergeCountInternal.add(frameIndex)
    }

    private fun promotePendingToMaster(p: CubeFragment) {
        master = p
        pending = null
        globalRotIdx = null
        centerPrototypes = null
        net = emptyNet()
        slotSupport.clear()
        conflictedSlots.clear()
        pendingMissStreak = 0
    }

    private data class ReadyCell(
        val face: Face,
        val cellIndex: Int,
        val qualitySum: Double,
        val dists: DoubleArray,
    )

    private fun solveGlobalColorsAndNet() {
        val m = master ?: return
        if (twistDetected) return

        val centerLabs = LinkedHashMap<Face, LabVec>()
        for (f in m.observedFaces) {
            val r = m.faceRobustLab(f, 4)
            if (r != null && r.qualitySum >= 0.40) {
                centerLabs[f] = r.lab
            }
        }
        if (centerLabs.size < 2) return

        val refLabs = Color.entries.associateWith { LabVec.from(ContinuousColorModel.REF_CENTER_LAB.getValue(it)) }

        if (centerLabs.size == 6) {
            val obsVals = centerLabs.values.toList()
            val refVals = refLabs.values.toList()
            val obsMean = meanLab(obsVals)
            val refMean = meanLab(refVals)
            val obsStd = stdLab(obsVals, obsMean, floor = 6.0)
            val refStd = stdLab(refVals, refMean, floor = 1e-9)

            val normCenters =
                centerLabs.mapValues { (_, lab) ->
                    LabVec(
                        (lab.l - obsMean.l) / obsStd.l,
                        (lab.a - obsMean.a) / obsStd.a,
                        (lab.b - obsMean.b) / obsStd.b,
                    )
                }
            val normRefs =
                refLabs.mapValues { (_, lab) ->
                    LabVec(
                        (lab.l - refMean.l) / refStd.l,
                        (lab.a - refMean.a) / refStd.a,
                        (lab.b - refMean.b) / refStd.b,
                    )
                }

            var bestCost = Double.POSITIVE_INFINITY
            var secondCost = Double.POSITIVE_INFINITY
            var bestRot = 0
            for (rotIdx in 0 until ContinuousCubeGeometry.SO3_COUNT) {
                var cost = 0.0
                for ((mf, nLab) in normCenters) {
                    val cf = ContinuousCubeGeometry.rotateFace(mf, rotIdx)
                    val cc = Color.home(cf)
                    cost += nLab.dist(normRefs.getValue(cc))
                }
                if (cost < bestCost) {
                    secondCost = bestCost
                    bestCost = cost
                    bestRot = rotIdx
                } else if (cost < secondCost) {
                    secondCost = cost
                }
            }
            if (secondCost - bestCost >= 0.35) {
                globalRotIdx = bestRot
            }
        } else if (globalRotIdx == null && centerLabs.size >= 3) {
            var bestCost = Double.POSITIVE_INFINITY
            var secondCost = Double.POSITIVE_INFINITY
            var bestRot = 0
            val denom = centerLabs.size.toDouble()
            for (rotIdx in 0 until ContinuousCubeGeometry.SO3_COUNT) {
                var cost = 0.0
                for ((mf, lab) in centerLabs) {
                    val cf = ContinuousCubeGeometry.rotateFace(mf, rotIdx)
                    val cc = Color.home(cf)
                    cost += lab.dist(refLabs.getValue(cc))
                }
                val avgCost = cost / denom
                if (avgCost < bestCost) {
                    secondCost = bestCost
                    bestCost = avgCost
                    bestRot = rotIdx
                } else if (avgCost < secondCost) {
                    secondCost = avgCost
                }
            }
            if (bestCost < 20.0 && (secondCost - bestCost) >= 8.0) {
                globalRotIdx = bestRot
            }
        }

        val rot = globalRotIdx ?: return

        val protos = LinkedHashMap<Color, LabVec>()
        for ((mf, lab) in centerLabs) {
            val cf = ContinuousCubeGeometry.rotateFace(mf, rot)
            protos[Color.home(cf)] = lab
        }
        centerPrototypes = protos

        val newNet = emptyNet()
        val newSupport = HashMap<FaceSlot, Double>()

        for (mf in centerLabs.keys) {
            val cf = ContinuousCubeGeometry.rotateFace(mf, rot)
            newNet.getValue(cf)[4] = Color.home(cf)
            newSupport[FaceSlot(cf, 4)] = 1e6
        }

        if (protos.size == 6) {
            val protoColors = Color.entries
            val protoLabs = protoColors.map { protos.getValue(it).toDomainLab() }
            val whiteProto = protos.getValue(Color.WHITE)
            val readyCells = ArrayList<ReadyCell>(48)

            for (mf in m.observedFaces) {
                for (mi in 0 until 9) {
                    if (mi == 4) continue
                    val r = m.faceRobustLab(mf, mi) ?: continue
                    if (r.qualitySum < 0.55 || r.count < 2) continue
                    val slot = ContinuousCubeGeometry.rotateSlot(mf, mi, rot)
                    val cellLab = r.lab.toDomainLab()
                    val dists =
                        DoubleArray(6) { k ->
                            ContinuousColorModel.whiteReferencedHueDistance(
                                lab = cellLab,
                                proto = protoLabs[k],
                                whiteA = whiteProto.a,
                                whiteB = whiteProto.b,
                                isWhiteProto = protoColors[k] == Color.WHITE,
                            )
                        }
                    readyCells.add(ReadyCell(slot.face, slot.cellIndex, r.qualitySum, dists))
                }
            }

            if (readyCells.size == 48) {
                val costMat =
                    Array(48) { i ->
                        val d = readyCells[i].dists
                        DoubleArray(6) { k -> d[k] * d[k] }
                    }
                val balAssign = ContinuousColorModel.solveBalanced8PerColor(costMat, cap = 8)
                for (i in readyCells.indices) {
                    val rc = readyCells[i]
                    val chosen = balAssign[i]
                    val chosenD = rc.dists[chosen]
                    var otherD = Double.POSITIVE_INFINITY
                    for (k in 0 until 6) {
                        if (k != chosen && rc.dists[k] < otherD) otherD = rc.dists[k]
                    }
                    val margin = otherD - chosenD
                    if (chosenD < 55.0) {
                        newNet.getValue(rc.face)[rc.cellIndex] = protoColors[chosen]
                        newSupport[FaceSlot(rc.face, rc.cellIndex)] = rc.qualitySum * (1.0 + 0.1 * max(0.0, margin))
                    }
                }
            } else {
                for (rc in readyCells) {
                    var bestIdx = 0
                    var bestD = Double.POSITIVE_INFINITY
                    var secondD = Double.POSITIVE_INFINITY
                    for (k in 0 until 6) {
                        val d = rc.dists[k]
                        if (d < bestD) {
                            secondD = bestD
                            bestD = d
                            bestIdx = k
                        } else if (d < secondD) {
                            secondD = d
                        }
                    }
                    val margin = secondD - bestD
                    if (bestD < 36.0 && margin >= 8.0) {
                        newNet.getValue(rc.face)[rc.cellIndex] = protoColors[bestIdx]
                        newSupport[FaceSlot(rc.face, rc.cellIndex)] = rc.qualitySum * (1.0 + 0.1 * margin)
                    }
                }
            }
        }

        // Immediate local physical invariants + evidence-weighted conflict reopening (up to 12 passes)
        val conflicted = LinkedHashSet<FaceSlot>()
        for (pass in 0 until 12) {
            val netView = Face.entries.associateWith { f -> newNet.getValue(f).toList() }
            val viols = ContinuousCubeGeometry.checkLocalInvariants(netView)
            if (viols.isEmpty()) break
            var reopenedAny = false
            for (v in viols) {
                val candSlots = v.slots.filter { s -> s.cellIndex != 4 && newNet.getValue(s.face)[s.cellIndex] != null }
                if (candSlots.isEmpty()) continue
                val weakest = candSlots.minBy { s -> newSupport[s] ?: 0.0 }
                newNet.getValue(weakest.face)[weakest.cellIndex] = null
                conflicted.add(weakest)
                reopenedAny = true
                break
            }
            if (!reopenedAny) break
        }

        net = newNet
        slotSupport = newSupport
        conflictedSlots = conflicted
    }

    private fun computeCue(
        anyGlare: Boolean,
        trackedNow: List<TrackedQuadOverlay>,
    ): ContinuousScanCue {
        if (twistDetected) return ContinuousScanCue.LayerTwistDetected
        val committed = Face.entries.sumOf { f -> net.getValue(f).count { it != null } }
        if (committed == Facelets.SIZE && conflictedSlots.isEmpty() && validateCompleteNet() != null) {
            return ContinuousScanCue.AllVerified
        }
        if (trackedNow.isEmpty()) return ContinuousScanCue.BringIntoView
        val curPending = pending
        if (curPending != null) {
            return if (curPending.observedFaces.size < 2) {
                ContinuousScanCue.TiltAdjacent
            } else {
                ContinuousScanCue.KeepTurningRealign
            }
        }
        val curMaster = master
        val confirmedCount = curMaster?.confirmedFaces()?.size ?: 0
        if (curMaster == null || confirmedCount < Face.entries.size) {
            val missing = Face.entries.size - confirmedCount
            return if (anyGlare) {
                ContinuousScanCue.TiltAwayLightRemaining(missing)
            } else {
                ContinuousScanCue.TurnRemaining(missing)
            }
        }
        if (conflictedSlots.isNotEmpty()) {
            val firstFace = conflictedSlots.sorted().first().face
            return ContinuousScanCue.ResolveConflict(firstFace)
        }
        if (committed == Facelets.SIZE) {
            return ContinuousScanCue.ResolveConflict(globalInvalidFace())
        }
        if (anyGlare) return ContinuousScanCue.TiltAwayLight
        return ContinuousScanCue.TiltRemainingStickers(committed)
    }

    private fun globalInvalidFace(): Face {
        val all = ArrayList<Color>(Facelets.SIZE)
        for (f in Face.entries) {
            val cells = net.getValue(f)
            for (i in 0 until 9) {
                all.add(cells[i] ?: return f)
            }
        }
        val problemPos =
            CubeValidator
                .problems(Facelets(all))
                .firstOrNull { it.positions.isNotEmpty() }
                ?.positions
                ?.firstOrNull()
        if (problemPos != null && problemPos in 0 until Facelets.SIZE) {
            return Face.entries[problemPos / 9]
        }
        return Face.entries.minByOrNull { f ->
            (0 until 9).filter { it != 4 }.sumOf { ci -> slotSupport[FaceSlot(f, ci)] ?: 0.0 }
        } ?: Face.U
    }

    private fun buildSnapshot(
        frameIndex: Int,
        visQuads: List<TrackedQuadOverlay>,
        cue: ContinuousScanCue,
    ): ContinuousScanSnapshot {
        val netCopy = Face.entries.associateWith { f -> net.getValue(f).toList() }
        val committed = Face.entries.sumOf { f -> netCopy.getValue(f).count { it != null } }
        val viols = ContinuousCubeGeometry.checkLocalInvariants(netCopy)
        val sortedConflicts = conflictedSlots.sorted()
        val mappedFaces = master?.confirmedFaces()?.size ?: 0
        val pendingFaces = pending?.observedFaces?.size ?: 0

        if (twistDetected) {
            return ContinuousScanSnapshot.TwistDetected(
                frameIndex = frameIndex,
                twistFrame = twistFrame ?: frameIndex,
                net = netCopy,
                conflictedSlots = sortedConflicts,
                trackedQuads = visQuads,
                globalAnchored = globalRotIdx != null,
                cue = cue,
                committedCount = committed,
                mappedFacesCount = mappedFaces,
                invariantViolationCount = viols.size,
                pendingFragmentFaces = pendingFaces,
            )
        }
        val validState = if (committed == Facelets.SIZE && viols.isEmpty()) validateCompleteNet() else null
        if (validState != null) {
            return ContinuousScanSnapshot.Complete(
                frameIndex = frameIndex,
                state = validState.reoriented(Orientation.HOME),
                net = netCopy,
                conflictedSlots = sortedConflicts,
                trackedQuads = visQuads,
                cue = cue,
                committedCount = committed,
                mappedFacesCount = Face.entries.size,
                pendingFragmentFaces = pendingFaces,
            )
        }
        return ContinuousScanSnapshot.Scanning(
            frameIndex = frameIndex,
            net = netCopy,
            conflictedSlots = sortedConflicts,
            trackedQuads = visQuads,
            globalAnchored = globalRotIdx != null,
            cue = cue,
            committedCount = committed,
            mappedFacesCount = mappedFaces,
            invariantViolationCount = viols.size,
            pendingFragmentFaces = pendingFaces,
        )
    }

    private companion object {
        val CORNER_ORDER = intArrayOf(0, 2, 8, 6)
        val OUTER_LINES = listOf(setOf(0, 1, 2), setOf(6, 7, 8), setOf(0, 3, 6), setOf(2, 5, 8))

        fun normalizeLogAwbInPlace(logAwb: DoubleArray) {
            val meanAwb = (logAwb[0] + logAwb[1] + logAwb[2]) / 3.0
            var maxAbsAwb = 0.0
            for (c in 0 until 3) {
                logAwb[c] -= meanAwb
                val a = abs(logAwb[c])
                if (a > maxAbsAwb) maxAbsAwb = a
            }
            if (maxAbsAwb > 0.36) {
                val scale = 0.36 / maxAbsAwb
                for (c in 0 until 3) {
                    logAwb[c] *= scale
                }
            }
        }

        fun emptyNet(): MutableMap<Face, Array<Color?>> = Face.entries.associateWithTo(LinkedHashMap()) { arrayOfNulls(9) }

        fun medianOf(values: DoubleArray): Double {
            if (values.isEmpty()) return 0.0
            val s = values.sortedArray()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else 0.5 * (s[mid - 1] + s[mid])
        }

        fun meanLab(labs: List<LabVec>): LabVec {
            val n = labs.size.toDouble()
            return LabVec(labs.sumOf { it.l } / n, labs.sumOf { it.a } / n, labs.sumOf { it.b } / n)
        }

        fun stdLab(
            labs: List<LabVec>,
            mean: LabVec,
            floor: Double,
        ): LabVec {
            val n = labs.size.toDouble()
            val vl = labs.sumOf { (it.l - mean.l) * (it.l - mean.l) } / n
            val va = labs.sumOf { (it.a - mean.a) * (it.a - mean.a) } / n
            val vb = labs.sumOf { (it.b - mean.b) * (it.b - mean.b) } / n
            return LabVec(max(sqrt(vl), floor), max(sqrt(va), floor), max(sqrt(vb), floor))
        }
    }
}
