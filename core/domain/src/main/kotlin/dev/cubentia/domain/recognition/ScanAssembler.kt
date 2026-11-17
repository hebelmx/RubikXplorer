package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult

/** Why a face is absent from a [ScanResult.Partial]. */
enum class ScanGap {
    /** No sample for this face. */
    MISSING,

    /** The capture was not ACCEPTABLE (blur, glare, low light, misaligned, incomplete). */
    POOR_QUALITY,

    /** Not nine valid CIELAB samples. */
    MALFORMED,

    /** No usable pose: no center or up color, an up color on the same or opposite face, or a non-quarter-turn orientation. */
    NO_POSE,

    /** More than one sample claims this face's center color. */
    DUPLICATE,

    /** The calibration lacks a color or holds an invalid center, so nothing could be classified. */
    UNCALIBRATED,
}

/**
 * A scan's read. [Full] has all six faces as 54 URFDLB [Facelets] (with one [StickerConfidence] each, same order); it is
 * not validated: 54 colors never count as a valid cube until `CubeValidator` and review accept them. [Partial] carries
 * the placed faces (colors in net order) and why each other face is absent; it is never completed from an expected state.
 */
sealed interface ScanResult {
    data class Full(
        val facelets: Facelets,
        val confidence: List<StickerConfidence>,
    ) : ScanResult

    data class Partial(
        val observation: PhysicalObservation.Partial,
        val confidence: Map<Face, List<StickerConfidence>>,
        val gaps: Map<Face, ScanGap>,
    ) : ScanResult
}

/** Which way [ScanAssembler] went for one scan ([ScanTrace.branch]). */
enum class ScanBranch {
    /** The calibration cannot classify: nothing assembled. */
    UNCALIBRATED,

    /** A gap left fewer than six faces: a Partial. */
    PARTIAL,

    /** Exactly one valid state. */
    RESOLVED,

    /** Several valid states: re-prompt the faces that differ. */
    AMBIGUOUS,

    /** No valid state and at most [ScanAssembler.MAX_REPROMPT_FACES] faces involved: re-prompt them. */
    NO_VALID_REPROMPT,

    /** No valid state and more faces involved: review the best fit, no re-prompt. */
    NO_VALID_REVIEW,
}

/**
 * Debug evidence of one assembly (device diagnostics, colors and counts only): each sample as read, how many distinct
 * valid states fit ([valid], null when no search ran), the faces to re-prompt ([involved]), the [branch] taken and the
 * clockwise quarter turns from pixel order each placed slot got ([turns]; the first candidate's for a searched face).
 */
data class ScanTrace(
    val samples: List<Sample>,
    val valid: Int?,
    val involved: List<Face>,
    val branch: ScanBranch,
    val turns: Map<Face, Int>,
) {
    /** One sample: the prompted [face], its pose colors, and its nine stickers classified in pixel order (null if malformed). */
    data class Sample(
        val face: Face,
        val centerColor: Color?,
        val upNeighborColor: Color?,
        val orientationDegrees: Int,
        val read: List<Color>?,
    )

    /** One line, `key=value` fields separated by spaces; colors as their initial letter (W Y G B R O), `-` for none. */
    fun describe(): String =
        listOf(
            "branch=$branch",
            "valid=${valid ?: "-"}",
            "involved=${involved.joinToString(",").ifEmpty { "-" }}",
            "turns=${Face.entries.filter { it in turns }.joinToString(",") { "$it${turns.getValue(it)}" }.ifEmpty { "-" }}",
        ).plus(
            samples.map { sample ->
                "${sample.face}:c=${letter(sample.centerColor)}/up=${letter(sample.upNeighborColor)}/o=${sample.orientationDegrees}" +
                    "/read=${sample.read?.joinToString("") { letter(it) } ?: "-"}"
            },
        ).joinToString(" ")

    companion object {
        /** A color's initial letter, `-` for none. */
        fun letter(color: Color?): String = color?.name?.take(1) ?: "-"
    }
}

/**
 * The only place samples are rotated into URFDLB order (AD-9). Each sample goes to the face whose home color is its
 * [FaceSample.centerColor]; a sample without one is reported as NO_POSE on the [FaceSample.face] it was prompted for
 * when no other sample claims that face. Its stickers turn from pixel order to the viewfinder by
 * `orientationDegrees / 90` clockwise quarter turns.
 *
 * A sample with an [FaceSample.upNeighborColor] (a prompt that named a top) then turns to the Kociemba net by the net
 * direction of the up neighbor's face around that slot. A sample without one (Story 10.14: the prompt named only the
 * center) is oriented from validity: when all six faces are otherwise placeable, every such face tries its 4 quarter
 * turns (centers fixed at home, posed faces at their prompted turn) over the classified colors, assignments giving the
 * same colors count as one, and the single distinct state `CubeValidator` accepts is the result. Assignments are pruned
 * by legal pieces first (a corner or edge showing a real cubie that no other piece of the assignment duplicates); only
 * all-legal ones are validated. With any other gap, unposed faces are NO_POSE: fewer than six faces cannot be oriented
 * from validity.
 *
 * When zero or several distinct states fit, the scan never guesses: [assemble] still returns `Full` of the first
 * candidate in enumeration order (the valid states if any, else the assignments with the most legal pieces), with every
 * non-center sticker of a face whose colors differ across the candidates UNCERTAIN, and [repromptFaces] names the faces
 * to re-prompt with a named top. With no valid state, it names them only when they are at most [MAX_REPROMPT_FACES]
 * (a real turn problem); more faces mean misread colors, which a named top cannot fix, so the scan goes straight to
 * review with that best fit.
 */
object ScanAssembler {
    const val STICKERS = 9

    /**
     * With no valid state, the most faces (center disagreements included) worth re-prompting with a named top: more
     * involved faces point to misread colors, not turns, so the scan goes to review with the best fit instead (PO
     * 2026-11-16, device session B2: a pale cube re-prompted 5 of 6 faces with white on top, uselessly). Several valid
     * states (true ambiguity) always re-prompt the faces that differ.
     */
    const val MAX_REPROMPT_FACES = 2
    private const val QUARTER = 90
    private const val TURNS = 4
    private const val CORNER_FACETS = 3
    private const val EDGE_FACETS = 2
    private const val FACES = 6
    private const val ILLEGAL = -1

    /** Source cell of each cell after one clockwise quarter turn of a row-major 3×3. */
    private val CLOCKWISE = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2)

    /** Faces around each slot in the [Facelets] net, clockwise from the top: top, right, bottom, left. */
    private val NET_NEIGHBORS: Map<Face, List<Face>> =
        mapOf(
            Face.U to listOf(Face.B, Face.R, Face.F, Face.L),
            Face.D to listOf(Face.F, Face.R, Face.B, Face.L),
            Face.R to listOf(Face.U, Face.B, Face.D, Face.F),
            Face.F to listOf(Face.U, Face.R, Face.D, Face.L),
            Face.L to listOf(Face.U, Face.F, Face.D, Face.B),
            Face.B to listOf(Face.U, Face.L, Face.D, Face.R),
        )

    private val HOME_FACE: Map<Color, Face> = Face.entries.associateBy { Color.home(it) }

    /** The cubie a corner shows, by its facets' home-face ordinals `f0 * 36 + f1 * 6 + f2` (slot facet order); -1 for none. */
    private val CORNER_CUBIE: IntArray =
        IntArray(FACES * FACES * FACES) { code ->
            val faces = listOf(code / (FACES * FACES), code / FACES % FACES, code % FACES).map { Face.entries[it] }
            val twist = faces.indexOfFirst { it == Face.U || it == Face.D }
            if (twist < 0) {
                ILLEGAL
            } else {
                FaceletTables.CORNER_FACES.indexOf(List(CORNER_FACETS) { faces[(twist + it) % CORNER_FACETS] })
            }
        }

    /** The cubie an edge shows, by its facets' home-face ordinals `f0 * 6 + f1`, straight or flipped; -1 for none. */
    private val EDGE_CUBIE: IntArray =
        IntArray(FACES * FACES) { code ->
            val faces = listOf(code / FACES, code % FACES).map { Face.entries[it] }
            val straight = FaceletTables.EDGE_FACES.indexOf(faces)
            if (straight >= 0) straight else FaceletTables.EDGE_FACES.indexOf(faces.reversed())
        }

    private val CORNERS = FaceletTables.CORNER_FACELETS.size
    private val PIECES = CORNERS + FaceletTables.EDGE_FACELETS.size

    fun assemble(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
    ): ScanResult = analyze(samples, calibration).result

    /**
     * The faces to re-prompt with a named top, in order: faces whose center read disagrees with their prompt (a logo
     * center, `centerPlain == false`, never counts), then in Guided Scan plan order the unposed faces that zero or several
     * valid states leave undecided. Empty when the scan resolves to one state, when there is nothing to ask (a gap, or
     * an uncalibrated scan), or when no state is valid and more than [MAX_REPROMPT_FACES] faces are involved.
     */
    fun repromptFaces(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
    ): List<Face> = analyze(samples, calibration).involved

    /** The [ScanTrace] of assembling [samples]: what [assemble] and [repromptFaces] decide, with the evidence (debug diagnostics). */
    fun trace(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
    ): ScanTrace {
        val outcome = analyze(samples, calibration)
        val classifiable = StickerClassifier.isValid(calibration)
        return ScanTrace(
            samples.map { sample ->
                val wellFormed = classifiable && sample.stickers.size == STICKERS && sample.stickers.all { it.isValid() }
                ScanTrace.Sample(
                    sample.face,
                    sample.centerColor,
                    sample.upNeighborColor,
                    sample.orientationDegrees,
                    if (wellFormed) sample.stickers.map { StickerClassifier.read(it, calibration.centers).color } else null,
                )
            },
            outcome.valid,
            outcome.involved,
            outcome.branch,
            outcome.turns,
        )
    }

    private class Outcome(
        val result: ScanResult,
        val involved: List<Face> = emptyList(),
        val branch: ScanBranch,
        val valid: Int? = null,
        val turns: Map<Face, Int> = emptyMap(),
    )

    private fun analyze(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
    ): Outcome {
        if (!StickerClassifier.isValid(calibration)) {
            return Outcome(
                ScanResult.Partial(
                    PhysicalObservation.Partial(emptyMap()),
                    emptyMap(),
                    Face.entries.associateWith { ScanGap.UNCALIBRATED },
                ),
                branch = ScanBranch.UNCALIBRATED,
            )
        }
        // Only a sample with a center color claims a slot. One without is reported (NO_POSE) on the face its prompt
        // named, and only when no sample claims that face.
        val bySlot =
            samples
                .mapNotNull { sample ->
                    sample.centerColor?.let { HOME_FACE.getValue(it) to sample }
                }.groupBy({ it.first }, { it.second })
        val unclaimed = samples.filter { it.centerColor == null }.map { it.face }.toSet()
        val placed = mutableMapOf<Face, Placement.Placed>()
        val gaps = mutableMapOf<Face, ScanGap>()
        Face.entries.forEach { slot ->
            val claims = bySlot[slot].orEmpty()
            when (claims.size) {
                0 -> {
                    gaps[slot] = if (slot in unclaimed) ScanGap.NO_POSE else ScanGap.MISSING
                }

                1 -> {
                    when (val placement = place(slot, claims.single(), calibration.centers)) {
                        is Placement.Placed -> placed[slot] = placement
                        is Placement.Gap -> gaps[slot] = placement.gap
                    }
                }

                else -> {
                    gaps[slot] = ScanGap.DUPLICATE
                }
            }
        }
        if (gaps.isEmpty()) return search(Face.entries.map { placed.getValue(it) })
        // Fewer than six faces cannot be oriented from validity.
        placed.filterValues { !it.posed }.keys.forEach { gaps[it] = ScanGap.NO_POSE }
        val faces = Face.entries.filter { it in placed && it !in gaps }
        return Outcome(
            ScanResult.Partial(
                PhysicalObservation.Partial(faces.associateWith { face -> placed.getValue(face).readings.map { it.color } }),
                faces.associateWith { face -> placed.getValue(face).readings.map { it.confidence } },
                Face.entries.filter { it in gaps }.associateWith { gaps.getValue(it) },
            ),
            branch = ScanBranch.PARTIAL,
            turns = faces.associateWith { placed.getValue(it).turns },
        )
    }

    private sealed interface Placement {
        /**
         * Readings in net order when [posed], else in viewfinder order (the turn is searched). [centerDisagrees]: the
         * center reads another color than the prompt named, and is not a logo.
         */
        data class Placed(
            val readings: List<StickerReading>,
            val posed: Boolean,
            val centerDisagrees: Boolean,
            val turns: Int = 0,
        ) : Placement

        data class Gap(
            val gap: ScanGap,
        ) : Placement
    }

    private fun place(
        slot: Face,
        sample: FaceSample,
        centers: Map<Color, Lab>,
    ): Placement {
        if (sample.quality != CaptureQuality.ACCEPTABLE) return Placement.Gap(ScanGap.POOR_QUALITY)
        if (sample.stickers.size != STICKERS || sample.stickers.any { !it.isValid() }) return Placement.Gap(ScanGap.MALFORMED)
        if (sample.orientationDegrees % QUARTER != 0) return Placement.Gap(ScanGap.NO_POSE)
        // Only samples whose center color is this slot's home color claim it.
        val center = Color.home(slot)
        val upFace = sample.upNeighborColor?.let(HOME_FACE::getValue)
        val direction = upFace?.let { NET_NEIGHBORS.getValue(slot).indexOf(it) }
        if (direction != null && direction < 0) return Placement.Gap(ScanGap.NO_POSE)
        val centerRead = StickerClassifier.read(sample.stickers[FaceSample.CENTER_INDEX], centers)
        val readings =
            sample.stickers.mapIndexed { index, sticker ->
                if (index != FaceSample.CENTER_INDEX) {
                    StickerClassifier.read(sticker, centers)
                } else {
                    // The prompt fixes the center's color; it is CONFIDENT only if the read agrees confidently.
                    val agrees = centerRead.color == center && centerRead.confidence == StickerConfidence.CONFIDENT
                    StickerReading(center, if (agrees) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN)
                }
            }
        val turns = sample.orientationDegrees / QUARTER + (direction ?: 0)
        return Placement.Placed(
            rotateClockwise(readings, turns),
            posed = direction != null,
            centerDisagrees = centerRead.color != center && sample.centerPlain != false,
            turns = Math.floorMod(turns, TURNS),
        )
    }

    /** One assignment of turns: the chosen option per face and the 54 home-face ordinals of its colors. */
    private class Assignment(
        val choice: IntArray,
        val grid: IntArray,
    )

    /** Six placed faces in URFDLB order; searches the turns of the unposed ones. */
    private fun search(faces: List<Placement.Placed>): Outcome {
        val options: List<List<List<StickerReading>>> =
            faces.map { face -> if (face.posed) listOf(face.readings) else List(TURNS) { rotateClockwise(face.readings, it) } }
        val optionGrids: List<List<IntArray>> =
            options.map { face -> face.map { readings -> IntArray(STICKERS) { HOME_FACE.getValue(readings[it].color).ordinal } } }
        val sizes = options.map { it.size }
        val total = sizes.fold(1) { product, size -> product * size }
        val valid = LinkedHashMap<List<Int>, Assignment>()
        val validated = HashMap<List<Int>, Boolean>()
        var best = LinkedHashMap<List<Int>, Assignment>()
        var mostLegal = -1
        val grid = IntArray(Facelets.SIZE)
        val choice = IntArray(FACES)
        for (code in 0 until total) {
            // U is the most significant digit, B the least; turn 0 first.
            var rest = code
            for (face in FACES - 1 downTo 0) {
                choice[face] = rest % sizes[face]
                rest /= sizes[face]
                System.arraycopy(optionGrids[face][choice[face]], 0, grid, face * STICKERS, STICKERS)
            }
            val legal = legal(grid).count { it }
            if (legal == PIECES) {
                val key = grid.toList()
                val accepted =
                    validated.getOrPut(key) {
                        CubeValidator.validate(Facelets(grid.map { Color.home(Face.entries[it]) })) is ValidationResult.Valid
                    }
                if (accepted && key !in valid) valid[key] = Assignment(choice.copyOf(), grid.copyOf())
            }
            if (legal > mostLegal) {
                mostLegal = legal
                best = LinkedHashMap()
            }
            if (legal == mostLegal) best.getOrPut(grid.toList()) { Assignment(choice.copyOf(), grid.copyOf()) }
        }
        val candidates = (if (valid.isNotEmpty()) valid.values else best.values).toList()
        val first = candidates.first()
        val differing = Face.entries.filter { face -> candidates.any { !sameFace(it.grid, first.grid, face) } }.toSet()
        val resolved = valid.size == 1
        val readings =
            Face.entries.flatMap { face ->
                options[face.ordinal][first.choice[face.ordinal]].mapIndexed { index, reading ->
                    val ambiguous = !resolved && face in differing && index != FaceSample.CENTER_INDEX
                    if (ambiguous) reading.copy(confidence = StickerConfidence.UNCERTAIN) else reading
                }
            }
        val result = ScanResult.Full(Facelets(readings.map { it.color }), readings.map { it.confidence })
        val turns =
            Face.entries.associateWith { face ->
                val placed = faces[face.ordinal]
                Math.floorMod(placed.turns + if (placed.posed) 0 else first.choice[face.ordinal], TURNS)
            }
        if (resolved) return Outcome(result, branch = ScanBranch.RESOLVED, valid = 1, turns = turns)
        val unposed = Face.entries.filter { !faces[it.ordinal].posed }.toSet()
        val undecided =
            if (valid.size >= 2) {
                differing
            } else {
                best.values
                    .flatMap { illegalFaces(it.grid) }
                    .toSet()
                    .ifEmpty { differing }
            }
        val order = GuidedScanPlan.steps.map { it.face }
        val disagreeing = order.filter { faces[it.ordinal].centerDisagrees }
        val involved = disagreeing + order.filter { it in undecided && it in unposed && it !in disagreeing }
        // No valid state and many faces involved: misread colors, not turns; review the best fit instead.
        if (valid.isEmpty() && involved.size > MAX_REPROMPT_FACES) {
            return Outcome(result, branch = ScanBranch.NO_VALID_REVIEW, valid = 0, turns = turns)
        }
        val branch = if (valid.isEmpty()) ScanBranch.NO_VALID_REPROMPT else ScanBranch.AMBIGUOUS
        return Outcome(result, involved, branch, valid.size, turns)
    }

    private fun sameFace(
        left: IntArray,
        right: IntArray,
        face: Face,
    ): Boolean = (face.ordinal * STICKERS until (face.ordinal + 1) * STICKERS).all { left[it] == right[it] }

    /** The cubie each piece (8 corners, then 12 edges, in slot order) shows, or -1 when none. */
    private fun cubies(grid: IntArray): IntArray {
        val pieces = IntArray(PIECES)
        FaceletTables.CORNER_FACELETS.forEachIndexed { slot, facets ->
            pieces[slot] = CORNER_CUBIE[grid[facets[0]] * FACES * FACES + grid[facets[1]] * FACES + grid[facets[2]]]
        }
        FaceletTables.EDGE_FACELETS.forEachIndexed { slot, facets ->
            val cubie = EDGE_CUBIE[grid[facets[0]] * FACES + grid[facets[1]]]
            pieces[CORNERS + slot] = if (cubie < 0) ILLEGAL else CORNERS + cubie
        }
        return pieces
    }

    /** Per piece: it shows a real cubie that no other piece of [grid] duplicates. */
    private fun legal(grid: IntArray): BooleanArray {
        val pieces = cubies(grid)
        val seen = IntArray(PIECES)
        pieces.forEach { if (it >= 0) seen[it]++ }
        return BooleanArray(PIECES) { pieces[it] >= 0 && seen[pieces[it]] == 1 }
    }

    /** Faces holding a sticker of an illegal piece of [grid]. */
    private fun illegalFaces(grid: IntArray): Set<Face> {
        val legal = legal(grid)
        val facelets = FaceletTables.CORNER_FACELETS + FaceletTables.EDGE_FACELETS
        return facelets.indices
            .filter { !legal[it] }
            .flatMap { piece -> facelets[piece].map { Face.entries[it / STICKERS] } }
            .toSet()
    }

    /** [cells] (a row-major 3×3) turned [turns] clockwise quarter turns. */
    internal fun <T> rotateClockwise(
        cells: List<T>,
        turns: Int,
    ): List<T> = (0 until Math.floorMod(turns, TURNS)).fold(cells) { grid, _ -> CLOCKWISE.map { grid[it] } }
}
