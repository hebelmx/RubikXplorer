package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult
import kotlin.math.sqrt

/**
 * Outcome of [CubieInvariantResolver.resolve]: either a single algebraically unique, physically valid 54-sticker cube
 * state whose deduced stickers all pass the post-uniqueness Lab plausibility guard, or [Unchanged] with a categorical
 * reason (AD-5, AD-9).
 */
sealed interface ResolutionOutcome {
    data class Resolved(
        val facelets: Facelets,
        val labs: List<Lab>,
        val resolvedIndices: Set<Int>,
    ) : ResolutionOutcome

    data class Unchanged(
        val reason: Reason,
    ) : ResolutionOutcome

    enum class Reason {
        UNCALIBRATED,
        INVALID_INPUT,
        NO_UNCERTAIN,
        TOO_MANY_UNCERTAIN,
        CONFIDENT_INVARIANT_VIOLATION,
        PLAUSIBILITY_REJECTED,
        NO_VALID_STATE,
        AMBIGUOUS,
        SEARCH_BUDGET_EXCEEDED,
    }
}

/**
 * Pure-domain cubie-invariant resolver for posed 6-face Quick Scan captures (Phase 1 spike; not wired into production).
 *
 * Separates algebraic uniqueness proving from Lab plausibility checking:
 * - Stage 0 verifies plain-center agreement, uncertain count cap, and piece-typed confident color/cubie invariants.
 * - Stage 1 runs iterative algebraic fixed-point propagation (corner chirality, piece-typed budgets, opposite/same-color
 *   exclusion, seen-cubie exclusion, forced singletons) with no Lab window pruning.
 * - Stage 2 runs bounded forward-checked MRV backtracking over all remaining algebraically legal candidates, validating
 *   leaves via [CubeValidator.validate] and aborting immediately on a 2nd valid state ([ResolutionOutcome.Reason.AMBIGUOUS])
 *   or on exceeding [MAX_SEARCH_NODES] ([ResolutionOutcome.Reason.SEARCH_BUDGET_EXCEEDED]).
 * - Stage 3 applies per-face light-adjusted Lab plausibility checks only to the proven-unique valid state.
 */
object CubieInvariantResolver {
    const val MAX_UNCERTAIN_STICKERS = 12
    const val MAX_SEARCH_NODES = 1024
    const val CANDIDATE_WINDOW_DELTA_E = 22.0
    const val MAX_PLAUSIBILITY_DELTA_E = 38.0
    const val DEFAULT_L_WEIGHT = 1.0

    private const val COLORS = 6
    private const val CORNERS_PER_COLOR = 4
    private const val EDGES_PER_COLOR = 4
    private const val ILLEGAL = -1

    private val CORNER_COLORS: List<List<Color>> =
        FaceletTables.CORNER_FACES.map { faces -> faces.map(Color::home) }

    private val EDGE_COLORS: List<List<Color>> =
        FaceletTables.EDGE_FACES.map { faces -> faces.map(Color::home) }

    /** Maps every valid clockwise adjacent corner color pair (Western scheme) to its unique 3rd clockwise color. */
    private val CORNER_CLOCKWISE_3RD: Map<Pair<Color, Color>, Color> =
        buildMap {
            CORNER_COLORS.forEach { (c0, c1, c2) ->
                put(c0 to c1, c2)
                put(c1 to c2, c0)
                put(c2 to c0, c1)
            }
        }

    /** Corner cubie index (0..7) by `c0 * 36 + c1 * 6 + c2` in clockwise slot order; -1 if illegal. */
    private val CORNER_CUBIE: IntArray =
        IntArray(COLORS * COLORS * COLORS) { code ->
            val triple =
                listOf(Color.entries[code / (COLORS * COLORS)], Color.entries[code / COLORS % COLORS], Color.entries[code % COLORS])
            val twist = triple.indexOfFirst { it == Color.WHITE || it == Color.YELLOW }
            if (twist < 0) {
                ILLEGAL
            } else {
                CORNER_COLORS.indexOf(List(3) { triple[(twist + it) % 3] })
            }
        }

    /** Edge cubie index (0..11) by `c0 * 6 + c1` (either orientation); -1 if illegal. */
    private val EDGE_CUBIE: IntArray =
        IntArray(COLORS * COLORS) { code ->
            val pair = listOf(Color.entries[code / COLORS], Color.entries[code % COLORS])
            val straight = EDGE_COLORS.indexOf(pair)
            if (straight >= 0) straight else EDGE_COLORS.indexOf(pair.reversed())
        }

    private val IS_CORNER: BooleanArray =
        BooleanArray(Facelets.SIZE).also { arr ->
            FaceletTables.CORNER_FACELETS.forEach { slot -> slot.forEach { arr[it] = true } }
        }

    /** Slot index in `CORNER_FACELETS` (if corner) or `EDGE_FACELETS` (if edge) for each net sticker; -1 for centers. */
    private val PIECE_SLOT: IntArray =
        IntArray(Facelets.SIZE) { ILLEGAL }.also { arr ->
            FaceletTables.CORNER_FACELETS.forEachIndexed { slot, facets -> facets.forEach { arr[it] = slot } }
            FaceletTables.EDGE_FACELETS.forEachIndexed { slot, facets -> facets.forEach { arr[it] = slot } }
        }

    /** Facet position within its piece (`0..2` for corners, `0..1` for edges); -1 for centers. */
    private val PIECE_FACET: IntArray =
        IntArray(Facelets.SIZE) { ILLEGAL }.also { arr ->
            FaceletTables.CORNER_FACELETS.forEach { facets -> facets.forEachIndexed { pos, idx -> arr[idx] = pos } }
            FaceletTables.EDGE_FACELETS.forEach { facets -> facets.forEachIndexed { pos, idx -> arr[idx] = pos } }
        }

    /** Opposite color on the standard Western 3×3 color scheme. */
    private fun oppositeColor(color: Color): Color =
        when (color) {
            Color.WHITE -> Color.YELLOW
            Color.YELLOW -> Color.WHITE
            Color.GREEN -> Color.BLUE
            Color.BLUE -> Color.GREEN
            Color.RED -> Color.ORANGE
            Color.ORANGE -> Color.RED
        }

    /**
     * Resolves uncertain stickers of six posed Quick Scan [samples] using gated [QuickScanClassification] confidences
     * and [ScanAssembler.placeAllPosed] net rotation.
     */
    fun resolve(
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
        lWeight: Double = DEFAULT_L_WEIGHT,
        candidateWindowDeltaE: Double = CANDIDATE_WINDOW_DELTA_E,
        maxPlausibilityDeltaE: Double = MAX_PLAUSIBILITY_DELTA_E,
    ): ResolutionOutcome {
        val self = SelfCalibration.from(samples, saved)
        if (self !is SelfCalibration.Calibrated) return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.UNCALIBRATED)
        val placed = ScanAssembler.placeAllPosed(samples) ?: return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.INVALID_INPUT)

        val savedValid = saved != null && StickerClassifier.isValid(saved)
        val netColors = ArrayList<Color>(Facelets.SIZE)
        val netConfidence = ArrayList<StickerConfidence>(Facelets.SIZE)
        val netLabs = ArrayList<Lab>(Facelets.SIZE)
        val plausibilityLabs = ArrayList<Lab>(Facelets.SIZE)
        val plausibilityCenters = ArrayList<Map<Color, Lab>>(COLORS)
        val centerPlain = BooleanArray(COLORS)

        placed.forEach { placement ->
            val sample = placement.sample
            centerPlain[placement.face.ordinal] = sample.centerPlain != false
            val gated = QuickScanClassification.read(sample, samples, saved)
            if (gated.size != ScanAssembler.STICKERS || gated.any { it == null }) {
                return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.INVALID_INPUT)
            }
            val rotatedReadings = ScanAssembler.rotatePlaced(placement, gated.filterNotNull())
            rotatedReadings.forEach { reading ->
                netColors.add(reading.color)
                netConfidence.add(reading.confidence)
            }
            netLabs.addAll(placement.labs)

            val corrected = if (savedValid) QuickScanClassification.corrected(sample, checkNotNull(saved)) else null
            val correctionApplied = corrected != null && corrected !== sample.stickers
            if (correctionApplied) {
                plausibilityLabs.addAll(ScanAssembler.rotatePlaced(placement, checkNotNull(corrected)))
                plausibilityCenters.add(checkNotNull(saved).centers)
            } else {
                plausibilityLabs.addAll(placement.labs)
                plausibilityCenters.add(QuickScanClassification.normalized(sample, samples, self.calibration.centers))
            }
        }

        return resolveNet(
            netColors = netColors,
            netConfidence = netConfidence,
            netLabs = netLabs,
            plausibilityLabs = plausibilityLabs,
            plausibilityCenters = plausibilityCenters,
            centerPlain = centerPlain,
            lWeight = lWeight,
            candidateWindowDeltaE = candidateWindowDeltaE,
            maxPlausibilityDeltaE = maxPlausibilityDeltaE,
        )
    }

    /** Internal net-order solver (URFDLB, 54 stickers) for direct unit testing and [resolve]. */
    internal fun resolveNet(
        netColors: List<Color>,
        netConfidence: List<StickerConfidence>,
        netLabs: List<Lab>,
        plausibilityLabs: List<Lab>,
        plausibilityCenters: List<Map<Color, Lab>>,
        centerPlain: BooleanArray = BooleanArray(COLORS) { true },
        lWeight: Double = DEFAULT_L_WEIGHT,
        candidateWindowDeltaE: Double = CANDIDATE_WINDOW_DELTA_E,
        maxPlausibilityDeltaE: Double = MAX_PLAUSIBILITY_DELTA_E,
    ): ResolutionOutcome {
        if (netColors.size != Facelets.SIZE ||
            netConfidence.size != Facelets.SIZE ||
            netLabs.size != Facelets.SIZE ||
            plausibilityLabs.size != Facelets.SIZE ||
            plausibilityCenters.size != COLORS ||
            centerPlain.size != COLORS ||
            netLabs.any { !it.isValid() } ||
            plausibilityLabs.any { !it.isValid() } ||
            plausibilityCenters.any { it.keys != Color.entries.toSet() || it.values.any { lab -> !lab.isValid() } }
        ) {
            return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.INVALID_INPUT)
        }

        // Stage 0: Plain-center agreement guard.
        for (face in Face.entries) {
            val centerIdx = face.ordinal * 9 + FaceSample.CENTER_INDEX
            if (centerPlain[face.ordinal]) {
                if (netConfidence[centerIdx] != StickerConfidence.CONFIDENT || netColors[centerIdx] != Color.home(face)) {
                    return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION)
                }
            }
        }

        val uncertain =
            (0 until Facelets.SIZE).filter { idx ->
                idx % 9 != FaceSample.CENTER_INDEX && netConfidence[idx] != StickerConfidence.CONFIDENT
            }
        if (uncertain.isEmpty()) return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.NO_UNCERTAIN)
        if (uncertain.size > MAX_UNCERTAIN_STICKERS) return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.TOO_MANY_UNCERTAIN)

        // Stage 0: Piece-typed confident color budgets & confident piece integrity.
        val assigned =
            Array<Color?>(Facelets.SIZE) { idx ->
                when {
                    idx % 9 == FaceSample.CENTER_INDEX -> Color.home(Face.entries[idx / 9])
                    netConfidence[idx] == StickerConfidence.CONFIDENT -> netColors[idx]
                    else -> null
                }
            }
        val remainingCorner = IntArray(COLORS) { CORNERS_PER_COLOR }
        val remainingEdge = IntArray(COLORS) { EDGES_PER_COLOR }
        for (idx in 0 until Facelets.SIZE) {
            if (idx % 9 == FaceSample.CENTER_INDEX) continue
            val color = assigned[idx] ?: continue
            if (IS_CORNER[idx]) {
                remainingCorner[color.ordinal]--
            } else {
                remainingEdge[color.ordinal]--
            }
        }
        if (remainingCorner.any { it < 0 } || remainingEdge.any { it < 0 }) {
            return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION)
        }

        val seenCorners = BooleanArray(FaceletTables.CORNER_FACELETS.size)
        val seenEdges = BooleanArray(FaceletTables.EDGE_FACELETS.size)
        for (facets in FaceletTables.CORNER_FACELETS) {
            val c0 = assigned[facets[0]]
            val c1 = assigned[facets[1]]
            val c2 = assigned[facets[2]]
            if (c0 != null && c1 != null && c2 != null) {
                val cubie = cornerCubie(c0, c1, c2)
                if (cubie == ILLEGAL || seenCorners[cubie]) {
                    return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION)
                }
                seenCorners[cubie] = true
            } else {
                val present = listOfNotNull(c0, c1, c2)
                if (present.size == 2 && (present[0] == present[1] || present[0] == oppositeColor(present[1]))) {
                    return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION)
                }
            }
        }
        for (facets in FaceletTables.EDGE_FACELETS) {
            val c0 = assigned[facets[0]]
            val c1 = assigned[facets[1]]
            if (c0 != null && c1 != null) {
                val cubie = edgeCubie(c0, c1)
                if (cubie == ILLEGAL || seenEdges[cubie]) {
                    return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.CONFIDENT_INVARIANT_VIOLATION)
                }
                seenEdges[cubie] = true
            }
        }

        // Stage 1: Algebraic fixed-point propagation (no Lab window pruning).
        val solver = SolverState(assigned, remainingCorner, remainingEdge, seenCorners, seenEdges)
        if (!solver.propagate(uncertain)) {
            return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.NO_VALID_STATE)
        }

        val remainingUnassigned = uncertain.filter { solver.assigned[it] == null }
        val uniqueFacelets: Facelets =
            if (remainingUnassigned.isEmpty()) {
                val candidate = Facelets(List(Facelets.SIZE) { solver.assigned[it]!! })
                if (CubeValidator.validate(candidate) !is ValidationResult.Valid) {
                    return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.NO_VALID_STATE)
                }
                candidate
            } else {
                // Precompute ascending weighted-ΔE color ordering per unassigned sticker (heuristic only, never pruning).
                val colorOrder =
                    remainingUnassigned.associateWith { idx ->
                        val face = idx / 9
                        val lab = plausibilityLabs[idx]
                        val centers = plausibilityCenters[face]
                        Color.entries.sortedBy { c -> weightedDeltaE(lab, centers.getValue(c), lWeight) }
                    }
                when (val search = solver.searchUnique(remainingUnassigned, colorOrder)) {
                    is SearchResult.Unique -> search.facelets
                    SearchResult.None -> return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.NO_VALID_STATE)
                    SearchResult.Ambiguous -> return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.AMBIGUOUS)
                    SearchResult.BudgetExceeded -> return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.SEARCH_BUDGET_EXCEEDED)
                }
            }

        // Stage 3: Post-uniqueness Lab plausibility guard.
        for (idx in uncertain) {
            val face = idx / 9
            val lab = plausibilityLabs[idx]
            val centers = plausibilityCenters[face]
            var minDist = Double.POSITIVE_INFINITY
            var chosenDist = Double.POSITIVE_INFINITY
            val chosen = uniqueFacelets.colors[idx]
            for (color in Color.entries) {
                val dist = weightedDeltaE(lab, centers.getValue(color), lWeight)
                if (dist < minDist) minDist = dist
                if (color == chosen) chosenDist = dist
            }
            if (chosenDist > minDist + candidateWindowDeltaE || chosenDist > maxPlausibilityDeltaE) {
                return ResolutionOutcome.Unchanged(ResolutionOutcome.Reason.PLAUSIBILITY_REJECTED)
            }
        }

        return ResolutionOutcome.Resolved(uniqueFacelets, netLabs, uncertain.toSet())
    }

    private fun cornerCubie(
        c0: Color,
        c1: Color,
        c2: Color,
    ): Int = CORNER_CUBIE[c0.ordinal * COLORS * COLORS + c1.ordinal * COLORS + c2.ordinal]

    private fun edgeCubie(
        c0: Color,
        c1: Color,
    ): Int = EDGE_CUBIE[c0.ordinal * COLORS + c1.ordinal]

    private fun weightedDeltaE(
        left: Lab,
        right: Lab,
        lWeight: Double,
    ): Double {
        val dl = left.l - right.l
        val da = left.a - right.a
        val db = left.b - right.b
        return sqrt(lWeight * dl * dl + da * da + db * db)
    }

    private sealed interface SearchResult {
        data class Unique(
            val facelets: Facelets,
        ) : SearchResult

        data object None : SearchResult

        data object Ambiguous : SearchResult

        data object BudgetExceeded : SearchResult
    }

    private class SolverState(
        val assigned: Array<Color?>,
        val remainingCorner: IntArray,
        val remainingEdge: IntArray,
        val seenCorners: BooleanArray,
        val seenEdges: BooleanArray,
    ) {
        private var nodesVisited = 0
        private var validCount = 0
        private var uniqueSolution: Facelets? = null
        private var abortedBudget = false
        private var abortedAmbiguous = false

        /** Returns false on an algebraic contradiction. */
        fun propagate(uncertain: List<Int>): Boolean {
            var changed = true
            while (changed) {
                changed = false
                // 1. Corner chirality completion.
                for (facets in FaceletTables.CORNER_FACELETS) {
                    val c0 = assigned[facets[0]]
                    val c1 = assigned[facets[1]]
                    val c2 = assigned[facets[2]]
                    val missingIdx: Int
                    val deduced: Color?
                    when {
                        c0 != null && c1 != null && c2 == null -> {
                            missingIdx = facets[2]
                            deduced = CORNER_CLOCKWISE_3RD[c0 to c1]
                        }

                        c1 != null && c2 != null && c0 == null -> {
                            missingIdx = facets[0]
                            deduced = CORNER_CLOCKWISE_3RD[c1 to c2]
                        }

                        c2 != null && c0 != null && c1 == null -> {
                            missingIdx = facets[1]
                            deduced = CORNER_CLOCKWISE_3RD[c2 to c0]
                        }

                        else -> {
                            continue
                        }
                    }
                    if (deduced == null || !tryAssign(missingIdx, deduced)) return false
                    changed = true
                }

                // 2 & 3. Algebraic domain check and forced-singleton deduction.
                for (idx in uncertain) {
                    if (assigned[idx] != null) continue
                    val legal = legalColors(idx)
                    when (legal.size) {
                        0 -> {
                            return false
                        }

                        1 -> {
                            if (!tryAssign(idx, legal.single())) return false
                            changed = true
                        }
                    }
                }
            }
            return true
        }

        fun searchUnique(
            unassigned: List<Int>,
            colorOrder: Map<Int, List<Color>>,
        ): SearchResult {
            dfs(unassigned, colorOrder)
            return when {
                abortedAmbiguous -> SearchResult.Ambiguous
                abortedBudget -> SearchResult.BudgetExceeded
                validCount == 1 -> SearchResult.Unique(checkNotNull(uniqueSolution))
                else -> SearchResult.None
            }
        }

        private fun dfs(
            unassigned: List<Int>,
            colorOrder: Map<Int, List<Color>>,
        ) {
            if (abortedAmbiguous || abortedBudget) return
            nodesVisited++
            if (nodesVisited > MAX_SEARCH_NODES) {
                abortedBudget = true
                return
            }

            // Pick MRV unassigned variable (fewest legal colors, tie-break by fewest unassigned facets on its piece, then index).
            var bestIdx = -1
            var bestCount = Int.MAX_VALUE
            var bestPieceMissing = Int.MAX_VALUE
            var bestLegal: List<Color>? = null
            var anyUnassigned = false

            for (idx in unassigned) {
                if (assigned[idx] != null) continue
                anyUnassigned = true
                val legal = legalColors(idx, colorOrder.getValue(idx))
                if (legal.isEmpty()) return // Forward-check dead end.
                val pieceMissing = unassignedOnPiece(idx)
                if (legal.size < bestCount ||
                    (legal.size == bestCount && pieceMissing < bestPieceMissing) ||
                    (legal.size == bestCount && pieceMissing == bestPieceMissing && idx < bestIdx)
                ) {
                    bestIdx = idx
                    bestCount = legal.size
                    bestPieceMissing = pieceMissing
                    bestLegal = legal
                }
            }

            if (!anyUnassigned) {
                val candidate = Facelets(List(Facelets.SIZE) { assigned[it]!! })
                if (CubeValidator.validate(candidate) is ValidationResult.Valid) {
                    validCount++
                    if (validCount == 1) {
                        uniqueSolution = candidate
                    } else {
                        abortedAmbiguous = true
                    }
                }
                return
            }

            for (color in checkNotNull(bestLegal)) {
                val completedCubie = assignWithUndo(bestIdx, color)
                if (completedCubie != INVALID_ASSIGN) {
                    // Lightweight chirality forward-check if this corner now has exactly 1 unassigned facet.
                    if (cornerChiralityFeasible(bestIdx)) {
                        dfs(unassigned, colorOrder)
                    }
                    undoAssign(bestIdx, color, completedCubie)
                }
                if (abortedAmbiguous || abortedBudget) return
            }
        }

        private fun cornerChiralityFeasible(idx: Int): Boolean {
            if (!IS_CORNER[idx]) return true
            val facets = FaceletTables.CORNER_FACELETS[PIECE_SLOT[idx]]
            val c0 = assigned[facets[0]]
            val c1 = assigned[facets[1]]
            val c2 = assigned[facets[2]]
            val deduced: Color =
                when {
                    c0 != null && c1 != null && c2 == null -> CORNER_CLOCKWISE_3RD[c0 to c1] ?: return false
                    c1 != null && c2 != null && c0 == null -> CORNER_CLOCKWISE_3RD[c1 to c2] ?: return false
                    c2 != null && c0 != null && c1 == null -> CORNER_CLOCKWISE_3RD[c2 to c0] ?: return false
                    else -> return true
                }
            if (remainingCorner[deduced.ordinal] <= 0) return false
            val cubie =
                when {
                    c2 == null -> cornerCubie(c0!!, c1!!, deduced)
                    c0 == null -> cornerCubie(deduced, c1!!, c2)
                    else -> cornerCubie(c0, deduced, c2)
                }
            return cubie != ILLEGAL && !seenCorners[cubie]
        }

        private fun unassignedOnPiece(idx: Int): Int {
            val facets =
                if (IS_CORNER[idx]) {
                    FaceletTables.CORNER_FACELETS[PIECE_SLOT[idx]]
                } else {
                    FaceletTables.EDGE_FACELETS[PIECE_SLOT[idx]]
                }
            return facets.count { assigned[it] == null }
        }

        private fun legalColors(
            idx: Int,
            order: List<Color> = Color.entries,
        ): List<Color> {
            val isCorner = IS_CORNER[idx]
            val budget = if (isCorner) remainingCorner else remainingEdge
            val slot = PIECE_SLOT[idx]
            val pos = PIECE_FACET[idx]
            val facets = if (isCorner) FaceletTables.CORNER_FACELETS[slot] else FaceletTables.EDGE_FACELETS[slot]
            val result = ArrayList<Color>(COLORS)
            for (color in order) {
                if (budget[color.ordinal] <= 0) continue
                if (!fitsPiece(isCorner, facets, pos, color)) continue
                result.add(color)
            }
            return result
        }

        private fun fitsPiece(
            isCorner: Boolean,
            facets: List<Int>,
            pos: Int,
            color: Color,
        ): Boolean {
            if (isCorner) {
                val c0 = if (pos == 0) color else assigned[facets[0]]
                val c1 = if (pos == 1) color else assigned[facets[1]]
                val c2 = if (pos == 2) color else assigned[facets[2]]
                if (c0 != null && c1 != null && c2 != null) {
                    val cubie = cornerCubie(c0, c1, c2)
                    return cubie != ILLEGAL && !seenCorners[cubie]
                }
                for (otherPos in 0..2) {
                    if (otherPos == pos) continue
                    val other = assigned[facets[otherPos]] ?: continue
                    if (other == color || other == oppositeColor(color)) return false
                }
                return true
            } else {
                val other = assigned[facets[1 - pos]] ?: return true
                if (other == color || other == oppositeColor(color)) return false
                val cubie = edgeCubie(if (pos == 0) color else other, if (pos == 0) other else color)
                return cubie != ILLEGAL && !seenEdges[cubie]
            }
        }

        /** Stage 1 permanent assignment with full invariant enforcement. */
        private fun tryAssign(
            idx: Int,
            color: Color,
        ): Boolean = assignWithUndo(idx, color) != INVALID_ASSIGN

        /**
         * Assigns [color] to [idx] if all piece and budget invariants hold.
         * Returns [INVALID_ASSIGN] if illegal, [NO_COMPLETED_CUBIE] if piece is still incomplete,
         * or the completed cubie index (`>= 0`) if the piece just completed.
         */
        private fun assignWithUndo(
            idx: Int,
            color: Color,
        ): Int {
            val isCorner = IS_CORNER[idx]
            val budget = if (isCorner) remainingCorner else remainingEdge
            if (budget[color.ordinal] <= 0) return INVALID_ASSIGN
            val slot = PIECE_SLOT[idx]
            val pos = PIECE_FACET[idx]
            val facets = if (isCorner) FaceletTables.CORNER_FACELETS[slot] else FaceletTables.EDGE_FACELETS[slot]
            if (!fitsPiece(isCorner, facets, pos, color)) return INVALID_ASSIGN

            assigned[idx] = color
            budget[color.ordinal]--
            if (isCorner) {
                val c0 = assigned[facets[0]]
                val c1 = assigned[facets[1]]
                val c2 = assigned[facets[2]]
                if (c0 != null && c1 != null && c2 != null) {
                    val cubie = cornerCubie(c0, c1, c2)
                    seenCorners[cubie] = true
                    return cubie
                }
            } else {
                val c0 = assigned[facets[0]]
                val c1 = assigned[facets[1]]
                if (c0 != null && c1 != null) {
                    val cubie = edgeCubie(c0, c1)
                    seenEdges[cubie] = true
                    return cubie
                }
            }
            return NO_COMPLETED_CUBIE
        }

        private fun undoAssign(
            idx: Int,
            color: Color,
            completedCubie: Int,
        ) {
            val isCorner = IS_CORNER[idx]
            if (completedCubie >= 0) {
                if (isCorner) seenCorners[completedCubie] = false else seenEdges[completedCubie] = false
            }
            if (isCorner) remainingCorner[color.ordinal]++ else remainingEdge[color.ordinal]++
            assigned[idx] = null
        }

        companion object {
            private const val INVALID_ASSIGN = -2
            private const val NO_COMPLETED_CUBIE = -1
        }
    }
}
