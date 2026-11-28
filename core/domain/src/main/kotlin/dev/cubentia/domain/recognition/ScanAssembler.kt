package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.recognition.continuous.ContinuousFaceHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff

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
 * [Full.labs], when present, holds each sticker's measured CIELAB in the same net order (rotated like the colors); it is
 * review evidence only (#174), never shown to the Learner.
 */
sealed interface ScanResult {
    data class Full(
        val facelets: Facelets,
        val confidence: List<StickerConfidence>,
        val labs: List<Lab>? = null,
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
    /**
     * One sample: the prompted [face], its pose colors, its nine stickers classified in pixel order (null if malformed)
     * and the pixel indexes read UNCERTAIN ([uncertain]).
     */
    data class Sample(
        val face: Face,
        val centerColor: Color?,
        val upNeighborColor: Color?,
        val orientationDegrees: Int,
        val read: List<Color>?,
        val uncertain: List<Int> = emptyList(),
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
                    "/read=${sample.read?.joinToString("") { letter(it) } ?: "-"}" +
                    "/unc=${sample.uncertain.joinToString(",").ifEmpty { "-" }}"
            },
        ).joinToString(" ")

    companion object {
        /** A color's initial letter, `-` for none. */
        fun letter(color: Color?): String = color?.name?.take(1) ?: "-"
    }
}

/**
 * How [ScanAssembler.orient] fits colour-only input (the Manual Cube Editor, AD-9). [Resolved]: the as-entered stickers
 * are valid ([turns] empty), or exactly one distinct valid state comes from turning faces, [turns] giving the clockwise
 * quarter turns (1..3) of each face whose colors changed, as the chosen assignment turned it. [Unresolved]: [valid]
 * distinct valid states fit (0 or at least 2) and [involved] names the faces to check (URFDLB order).
 */
sealed interface FaceTurnFit {
    data class Resolved(
        val facelets: Facelets,
        val turns: Map<Face, Int>,
    ) : FaceTurnFit {
        /** The faces in [turns], in URFDLB order. */
        val turned: List<Face> get() = Face.entries.filter { it in turns }
    }

    data class Unresolved(
        val valid: Int,
        val involved: List<Face>,
    ) : FaceTurnFit
}

sealed interface CornerLastLayer {
    data class View(
        val view: LastLayerView,
        val lowerRows: List<Pair<Color?, Color>>,
    ) : CornerLastLayer

    data object NotOnRing : CornerLastLayer
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
    fun cornerView(
        samples: List<FrameStats?>,
        calibration: CalibrationCenters,
    ): CornerView? {
        if (samples.size != 3 || !StickerClassifier.isValid(calibration)) return null
        val faces =
            samples.map { sample ->
                if (sample == null || FrameQuality.assessShutter(sample) != CaptureQuality.ACCEPTABLE) return null
                sample.cells.mapIndexed { index, cell ->
                    val value = cell?.value ?: return null
                    if (!value.isValid()) return null
                    val reading = StickerClassifier.read(value, calibration.centers)
                    reading.color.takeIf { index == FaceSample.CENTER_INDEX || reading.confidence == StickerConfidence.CONFIDENT }
                }
            }
        return CornerView(faces[0], faces[1], faces[2])
    }

    fun lastLayerView(corner: CornerView): CornerLastLayer {
        val (top, left, right) = corner
        val up = checkNotNull(top[4])
        val front = checkNotNull(left[4])
        val upFace = Face.entries.first { Color.home(it) == up }
        if (front == up || front == Color.home(upFace.opposite)) return CornerLastLayer.NotOnRing
        val stickers =
            listOf(top[7], top[6]) + left.take(3) +
                listOf(top[5], top[8]) + right.take(3) +
                listOf(top[1], top[2], null, null, null, top[3], top[0], null, null, null)
        val view = LastLayerView.partial(up, front, stickers)
        if (right[4] != view.ring[1]) return CornerLastLayer.NotOnRing
        val lowerRows =
            listOf(left, right).flatMap { side ->
                val center = checkNotNull(side[4])
                side.drop(3).filterIndexed { index, _ -> index != 1 }.map { it to center }
            }
        return CornerLastLayer.View(view, lowerRows)
    }

    fun lastLayerView(
        first: CornerView,
        opposite: CornerView,
    ): CornerLastLayer {
        val a = lastLayerView(first) as? CornerLastLayer.View ?: return CornerLastLayer.NotOnRing
        val b = lastLayerView(opposite) as? CornerLastLayer.View ?: return CornerLastLayer.NotOnRing
        if (a.view.lastLayer != b.view.lastLayer || a.view.ring.drop(2) != b.view.ring.take(2)) {
            return CornerLastLayer.NotOnRing
        }
        // A half turn exchanges F/R with B/L, including the U edge and corner in each block.
        val half = LastLayerView.STICKERS / 2
        val rotated = b.view.stickers.drop(half) + b.view.stickers.take(half)
        val merged =
            a.view.stickers.zip(rotated).map { (one, two) ->
                if (one != null && two != null && one != two) null else one ?: two
            }
        return CornerLastLayer.View(
            LastLayerView.partial(a.view.lastLayer, a.view.ring.first(), merged),
            a.lowerRows + b.lowerRows,
        )
    }

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

    /** A blank sticker in a search grid (a retake's kept face, Story 10.16): its piece is never legal. */
    private const val BLANK = -1

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

    /**
     * The scan of [samples] read against [calibration]. [blanks] (Story 10.21, Quick Scan review) names per sample face
     * the stickers (viewfinder order) to leave UNCERTAIN whatever they read: a kept corner face's gated-uncertain ones.
     */
    fun assemble(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
        blanks: Map<Face, Set<Int>> = emptyMap(),
    ): ScanResult = analyze(samples, calibration, blanks = blanks).result

    /** The faces around [face] in the [Facelets] net: top, right, bottom, left (the hold a face is entered in). */
    fun netNeighbors(face: Face): List<Face> = NET_NEIGHBORS.getValue(face)

    /** One posed sample placed into URFDLB net order by [placeAllPosed], keeping its net-ordered Labs and turn count. */
    internal data class PosedPlacement(
        val face: Face,
        val sample: FaceSample,
        val turns: Int,
        val labs: List<Lab>,
    )

    /**
     * Places six posed, ACCEPTABLE, well-formed samples (one per home center color) into URFDLB net order using the
     * same pose and turn rules as [place]; null if any face is missing, duplicated, poor quality, malformed, or lacks a
     * valid orthogonal [FaceSample.upNeighborColor]. Keeps all sample-to-net rotation inside [ScanAssembler] (AD-9).
     */
    internal fun placeAllPosed(samples: List<FaceSample>): List<PosedPlacement>? {
        if (samples.size != FACES) return null
        val bySlot =
            samples
                .mapNotNull { sample -> sample.centerColor?.let { HOME_FACE.getValue(it) to sample } }
                .groupBy({ it.first }, { it.second })
        return Face.entries.map { slot ->
            val claims = bySlot[slot].orEmpty()
            if (claims.size != 1) return null
            val sample = claims.single()
            if (sample.quality != CaptureQuality.ACCEPTABLE) return null
            if (sample.stickers.size != STICKERS || sample.stickers.any { !it.isValid() }) return null
            if (sample.orientationDegrees % QUARTER != 0) return null
            val upFace = sample.upNeighborColor?.let(HOME_FACE::getValue) ?: return null
            val direction = NET_NEIGHBORS.getValue(slot).indexOf(upFace)
            if (direction < 0) return null
            val turns = Math.floorMod(sample.orientationDegrees / QUARTER + direction, TURNS)
            PosedPlacement(slot, sample, turns, rotateClockwise(sample.stickers, turns))
        }
    }

    /** Rotates a 9-cell list from pixel order into URFDLB net order using [placement]'s validated turns. */
    internal fun <T> rotatePlaced(
        placement: PosedPlacement,
        cells: List<T>,
    ): List<T> = rotateClockwise(cells, placement.turns)

    /**
     * Fits colour-only [facelets] (centers at home) by per-face quarter turns: valid as entered wins with nothing
     * turned; otherwise each face's 9 colors (center fixed) try their 4 clockwise turns, and exactly one distinct valid
     * state resolves, naming the faces it turned. Zero or several valid states are [FaceTurnFit.Unresolved]: with
     * several, the faces that differ across them; with none, the faces holding an illegal piece of the best candidates,
     * falling back to the faces that differ. [FaceTurnFit.Unresolved.involved] is never padded, so it may be empty (a
     * twisted corner, flipped edge or parity error with every piece legal: no face turn can fix it). Pure; never throws.
     */
    fun orient(facelets: Facelets): FaceTurnFit {
        if (CubeValidator.validate(facelets) is ValidationResult.Valid) return FaceTurnFit.Resolved(facelets, emptyMap())
        val colors = facelets.colors
        val input = IntArray(Facelets.SIZE) { HOME_FACE.getValue(colors[it]).ordinal }
        val options =
            Face.entries.map { face ->
                val cells = input.toList().subList(face.ordinal * STICKERS, (face.ordinal + 1) * STICKERS)
                List(TURNS) { turn -> rotateClockwise(cells, turn).toIntArray() }
            }
        val found = enumerate(options)

        if (found.valid.size == 1) {
            val fit = found.valid.values.single()
            val turns =
                Face.entries
                    .filter { !sameFace(fit.grid, input, it) }
                    .associateWith { fit.choice[it.ordinal] }
            return FaceTurnFit.Resolved(Facelets(fit.grid.map { Color.home(Face.entries[it]) }), turns)
        }
        val involved =
            if (found.valid.size >= 2) {
                found.differing
            } else {
                found.best.values
                    .flatMap { illegalFaces(it.grid) }
                    .toSet()
                    .ifEmpty { found.differing }
            }
        return FaceTurnFit.Unresolved(found.valid.size, Face.entries.filter { it in involved })
    }

    /**
     * The unposed faces to re-prompt with a named top: first faces whose plain center read disagrees with their
     * prompt, then faces that zero or several valid states leave undecided, in Guided Scan plan order within each group.
     * Empty when the scan resolves to one state, when there is nothing to ask (a gap or an uncalibrated scan), or when
     * no state is valid and more than [MAX_REPROMPT_FACES] faces are involved. [repromptPosed] also makes already posed
     * faces eligible in both groups for Quick Scan's corner-to-grid hand-off.
     */
    fun repromptFaces(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
        repromptPosed: Boolean = false,
    ): List<Face> = analyze(samples, calibration, repromptPosed).involved

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
                val reads = if (wellFormed) sample.stickers.map { StickerClassifier.read(it, calibration.centers) } else null
                ScanTrace.Sample(
                    sample.face,
                    sample.centerColor,
                    sample.upNeighborColor,
                    sample.orientationDegrees,
                    reads?.map { it.color },
                    reads?.indices?.filter { reads[it].confidence == StickerConfidence.UNCERTAIN }.orEmpty(),
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
        repromptPosed: Boolean = false,
        blanks: Map<Face, Set<Int>> = emptyMap(),
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
                    when (val placement = place(slot, claims.single(), calibration.centers, blanks[claims.single().face].orEmpty())) {
                        is Placement.Placed -> placed[slot] = placement
                        is Placement.Gap -> gaps[slot] = placement.gap
                    }
                }

                else -> {
                    gaps[slot] = ScanGap.DUPLICATE
                }
            }
        }
        if (gaps.isEmpty()) return search(Face.entries.map { placed.getValue(it) }, repromptPosed)
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
            val labs: List<Lab>,
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
        blanks: Set<Int> = emptySet(),
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
                if (index in blanks && index != FaceSample.CENTER_INDEX) {
                    StickerClassifier.read(sticker, centers).copy(confidence = StickerConfidence.UNCERTAIN)
                } else if (index != FaceSample.CENTER_INDEX) {
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
            rotateClockwise(sample.stickers, turns),
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
    private fun search(
        faces: List<Placement.Placed>,
        repromptPosed: Boolean,
    ): Outcome {
        val options: List<List<List<StickerReading>>> =
            faces.map { face -> if (face.posed) listOf(face.readings) else List(TURNS) { rotateClockwise(face.readings, it) } }
        val found = enumerate(options.map { face -> face.map(::gridOf) })
        val valid = found.valid
        val best = found.best
        val first = found.first
        val differing = found.differing
        val resolved = valid.size == 1
        val labs =
            Face.entries.flatMap { face ->
                val placed = faces[face.ordinal]
                if (placed.posed) placed.labs else rotateClockwise(placed.labs, first.choice[face.ordinal])
            }
        val readings =
            Face.entries.flatMap { face ->
                options[face.ordinal][first.choice[face.ordinal]].mapIndexed { index, reading ->
                    val ambiguous = !resolved && face in differing && index != FaceSample.CENTER_INDEX
                    if (ambiguous) reading.copy(confidence = StickerConfidence.UNCERTAIN) else reading
                }
            }
        val result = ScanResult.Full(Facelets(readings.map { it.color }), readings.map { it.confidence }, labs)
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
        val disagreeing = order.filter { faces[it.ordinal].centerDisagrees && (repromptPosed || it in unposed) }
        val involved = disagreeing + order.filter { it in undecided && (repromptPosed || it in unposed) && it !in disagreeing }
        // No valid state and many faces involved: misread colors, not turns; review the best fit instead.
        if (valid.isEmpty() && involved.size > MAX_REPROMPT_FACES) {
            return Outcome(result, branch = ScanBranch.NO_VALID_REVIEW, valid = 0, turns = turns)
        }
        val branch = if (valid.isEmpty()) ScanBranch.NO_VALID_REPROMPT else ScanBranch.AMBIGUOUS
        return Outcome(result, involved, branch, valid.size, turns)
    }

    /** The home-face ordinals of [readings] (one face, net order); [BLANK] for a sticker with no color. */
    private fun gridOf(readings: List<StickerReading?>): IntArray =
        IntArray(STICKERS) { index -> readings[index]?.let { HOME_FACE.getValue(it.color).ordinal } ?: BLANK }

    /**
     * Every assignment of one option per face ([optionGrids], URFDLB, each option a face's 9 home-face ordinals): the
     * distinct valid states ([Enumeration.valid]) and the distinct assignments with the most legal pieces
     * ([Enumeration.best]), both in enumeration order (U the most significant digit, B the least; option 0 first).
     */
    private fun enumerate(optionGrids: List<List<IntArray>>): Enumeration {
        val sizes = optionGrids.map { it.size }
        val total = sizes.fold(1) { product, size -> product * size }
        val valid = LinkedHashMap<List<Int>, Assignment>()
        val validated = HashMap<List<Int>, Boolean>()
        var best = LinkedHashMap<List<Int>, Assignment>()
        var mostLegal = -1
        val grid = IntArray(Facelets.SIZE)
        val choice = IntArray(FACES)
        for (code in 0 until total) {
            var rest = code
            for (face in FACES - 1 downTo 0) {
                choice[face] = rest % sizes[face]
                rest /= sizes[face]
                System.arraycopy(optionGrids[face][choice[face]], 0, grid, face * STICKERS, STICKERS)
            }
            val legal = legal(grid).count { it }
            // A blank sticker makes its piece illegal, so only an all-colored grid is ever validated.
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
        return Enumeration(valid, best)
    }

    /** What [enumerate] found; [first] is the first candidate and [differing] the faces whose colors vary across them. */
    private class Enumeration(
        val valid: LinkedHashMap<List<Int>, Assignment>,
        val best: LinkedHashMap<List<Int>, Assignment>,
    ) {
        val candidates: List<Assignment> = (if (valid.isNotEmpty()) valid.values else best.values).toList()
        val first: Assignment = candidates.first()
        val differing: Set<Face> = Face.entries.filter { face -> candidates.any { !sameFace(it.grid, first.grid, face) } }.toSet()
    }

    /**
     * A retake from the review (Story 10.16): [kept] faces stay exactly as reviewed (each a single fixed option; a null
     * sticker is blank, which makes its piece illegal, and comes out UNCERTAIN), and each [retaken] sample, on the face
     * it was prompted for ([FaceSample.face]), is placed as [assemble] places it and, unless it names a top, searched
     * over its 4 quarter turns with every other face held fixed. The result is the first valid state, or with none the
     * first assignment with the most legal pieces; unless exactly one valid state fits, every non-center sticker of a
     * retaken face whose colors differ across those candidates is UNCERTAIN. It never asks for a re-prompt.
     *
     * A retaken sample that cannot be placed (not ACCEPTABLE, malformed, no quarter-turn pose, or claiming another
     * face's center), a face with no sample and no kept colors, and every retaken face when [calibration] cannot
     * classify, are blank: only their centers are colored. [ScanResult.Full.labs] holds, in net order, the measured CIELAB of
     * each retaken face that was placed; every other entry (kept faces, and retaken faces that could not be placed) is a
     * placeholder (the calibration center of the color shown), never a measurement; it is null when [calibration]
     * cannot classify.
     */
    fun assembleRetake(
        kept: Map<Face, List<Color?>>,
        retaken: List<FaceSample>,
        calibration: CalibrationCenters,
    ): ScanResult.Full {
        val classifiable = StickerClassifier.isValid(calibration)
        val samples = retaken.groupBy { it.face }
        val placed: Map<Face, Placement.Placed> =
            if (!classifiable) {
                emptyMap()
            } else {
                samples
                    .filter { (face, claims) -> face !in kept && claims.size == 1 && claims.single().centerColor == Color.home(face) }
                    .mapNotNull { (face, claims) ->
                        (place(face, claims.single(), calibration.centers) as? Placement.Placed)?.let {
                            face to
                                it
                        }
                    }.toMap()
            }
        val options: List<List<List<StickerReading?>>> =
            Face.entries.map { face ->
                val fixed = placed[face]
                when {
                    fixed == null -> listOf(fixedFace(face, kept[face]))
                    fixed.posed -> listOf(fixed.readings)
                    else -> List(TURNS) { rotateClockwise(fixed.readings, it) }
                }
            }
        val found = enumerate(options.map { face -> face.map(::gridOf) })
        val resolved = found.valid.size == 1
        val readings =
            Face.entries.flatMap { face ->
                options[face.ordinal][found.first.choice[face.ordinal]].mapIndexed { index, reading ->
                    val ambiguous = !resolved && face in placed && face in found.differing && index != FaceSample.CENTER_INDEX
                    when {
                        reading == null -> StickerReading(Color.home(face), StickerConfidence.UNCERTAIN)
                        ambiguous -> reading.copy(confidence = StickerConfidence.UNCERTAIN)
                        else -> reading
                    }
                }
            }
        val labs =
            if (!classifiable) {
                null
            } else {
                Face.entries.flatMap { face ->
                    val fixed = placed[face]
                    when {
                        fixed == null -> {
                            readings
                                .subList(
                                    face.ordinal * STICKERS,
                                    (face.ordinal + 1) * STICKERS,
                                ).map { calibration.centers.getValue(it.color) }
                        }

                        fixed.posed -> {
                            fixed.labs
                        }

                        else -> {
                            rotateClockwise(fixed.labs, found.first.choice[face.ordinal])
                        }
                    }
                }
            }
        return ScanResult.Full(Facelets(readings.map { it.color }), readings.map { it.confidence }, labs)
    }

    /**
     * Story 10.17: Guided Scan handover from a partial continuous scan [handoff]. Confirmed faces
     * in [handoff] keep their colors untouched (never reclassified); faces with
     * [ContinuousFaceHandoff.orientationLocked] stay fixed while unanchored/isolated faces search
     * their 4 quarter turns alongside unposed [added] Guided samples. Returns [ScanResult.Full]
     * when all six faces are placed, or [ScanResult.Partial] when gaps remain; never throws.
     */
    fun assembleHandover(
        handoff: ContinuousScanHandoff,
        added: List<FaceSample>,
        saved: CalibrationCenters? = null,
        preserveUnread: Boolean = false,
    ): ScanResult {
        val keptByFace: Map<Face, ContinuousFaceHandoff> =
            if (handoff.globalAnchored) handoff.faces else emptyMap()
        val filteredAdded = added.filter { sample -> sample.centerColor?.let(HOME_FACE::getValue) !in keptByFace }
        val fallbackCenters = buildHandoverFallbackCenters(handoff, saved)
        val addedBySlot =
            filteredAdded
                .mapNotNull { sample -> sample.centerColor?.let { HOME_FACE.getValue(it) to sample } }
                .groupBy({ it.first }, { it.second })
        val unclaimed = filteredAdded.filter { it.centerColor == null }.map { it.face }.toSet()

        val keptSamples = keptSamples(handoff)

        val calibration: CalibrationCenters? =
            if (keptByFace.size == Face.entries.size) {
                fallbackCenters?.takeIf(StickerClassifier::isValid)
            } else {
                val derivedWithKept =
                    if (saved == null && keptSamples.size == keptByFace.size &&
                        keptSamples.size + filteredAdded.size >= Face.entries.size
                    ) {
                        (SelfCalibration.from(keptSamples + filteredAdded, saved = null) as? SelfCalibration.Calibrated)?.calibration
                    } else {
                        null
                    }
                derivedWithKept
                    ?: (SelfCalibration.from(filteredAdded, fallbackCenters) as? SelfCalibration.Calibrated)?.calibration
            }
        val classifyUnclassified = !preserveUnread && handoff.calibration == null && keptByFace.size < Face.entries.size

        val placedAdded = mutableMapOf<Face, Placement.Placed>()
        val gaps = mutableMapOf<Face, ScanGap>()
        for (slot in Face.entries) {
            if (slot in keptByFace) continue
            val claims = addedBySlot[slot].orEmpty()
            when {
                claims.isEmpty() -> {
                    gaps[slot] = if (slot in unclaimed) ScanGap.NO_POSE else ScanGap.MISSING
                }

                claims.size > 1 -> {
                    gaps[slot] = ScanGap.DUPLICATE
                }

                calibration == null -> {
                    gaps[slot] = ScanGap.UNCALIBRATED
                }

                else -> {
                    when (val placement = place(slot, claims.single(), calibration.centers)) {
                        is Placement.Placed -> placedAdded[slot] = placement
                        is Placement.Gap -> gaps[slot] = placement.gap
                    }
                }
            }
        }

        if (gaps.isEmpty()) {
            val options: List<List<List<StickerReading?>>> =
                Face.entries.map { face ->
                    val kept = keptByFace[face]
                    if (kept != null) {
                        val base = handoffFaceReadings(face, kept, calibration, classifyUnclassified)
                        if (kept.orientationLocked) listOf(base) else List(TURNS) { rotateClockwise(base, it) }
                    } else {
                        val placed = placedAdded.getValue(face)
                        if (placed.posed) listOf(placed.readings) else List(TURNS) { rotateClockwise(placed.readings, it) }
                    }
                }
            val found = enumerate(options.map { face -> face.map(::gridOf) })
            val resolved = found.valid.size == 1
            val readings =
                Face.entries.flatMap { face ->
                    val kept = keptByFace[face]
                    val chosen = options[face.ordinal][found.first.choice[face.ordinal]]
                    chosen.mapIndexed { index, reading ->
                        val canSpin = kept == null || !kept.orientationLocked
                        val ambiguous = !resolved && canSpin && face in found.differing && index != FaceSample.CENTER_INDEX
                        when {
                            reading == null -> StickerReading(Color.home(face), StickerConfidence.UNCERTAIN)
                            ambiguous -> reading.copy(confidence = StickerConfidence.UNCERTAIN)
                            else -> reading
                        }
                    }
                }
            val labs: List<Lab>? =
                calibration?.let { cal ->
                    Face.entries.flatMap { face ->
                        val kept = keptByFace[face]
                        val turn = found.first.choice[face.ordinal]
                        if (kept != null) {
                            val alignedLabs = if (kept.orientationLocked) kept.labs else rotateClockwise(kept.labs, turn)
                            List(STICKERS) { idx ->
                                alignedLabs[idx]?.takeIf { it.isValid() }
                                    ?: cal.centers.getValue(readings[face.ordinal * STICKERS + idx].color)
                            }
                        } else {
                            val placed = placedAdded.getValue(face)
                            if (placed.posed) placed.labs else rotateClockwise(placed.labs, turn)
                        }
                    }
                }
            return ScanResult.Full(Facelets(readings.map { it.color }), readings.map { it.confidence }, labs)
        }

        // Fewer than six faces: unposed added faces and unoriented kept faces cannot be oriented from validity.
        placedAdded.filterValues { !it.posed }.keys.forEach { gaps[it] = ScanGap.NO_POSE }
        keptByFace.filterValues { !it.orientationLocked }.keys.forEach { gaps[it] = ScanGap.NO_POSE }
        val obsFaces = LinkedHashMap<Face, List<Color>>()
        val confFaces = LinkedHashMap<Face, List<StickerConfidence>>()
        for (face in Face.entries) {
            if (face in gaps) continue
            val kept = keptByFace[face]
            if (kept != null) {
                val readings = handoffFaceReadings(face, kept, calibration, classifyUnclassified)
                obsFaces[face] = readings.map { it?.color ?: Color.home(face) }
                confFaces[face] = readings.map { it?.confidence ?: StickerConfidence.UNCERTAIN }
            } else {
                val placed = placedAdded[face] ?: continue
                obsFaces[face] = placed.readings.map { it.color }
                confFaces[face] = placed.readings.map { it.confidence }
            }
        }
        return ScanResult.Partial(
            PhysicalObservation.Partial(obsFaces),
            confFaces,
            Face.entries.filter { it in gaps }.associateWith { gaps.getValue(it) },
        )
    }

    /** The kept faces of an anchored [handoff] whose nine Labs are all valid, as samples; none when it is unanchored. */
    internal fun keptSamples(handoff: ContinuousScanHandoff): List<FaceSample> =
        handoff.faces.takeIf { handoff.globalAnchored }.orEmpty().mapNotNull { (face, fh) ->
            val validLabs = fh.labs.takeIf { it.size == STICKERS && it.all { l -> l != null && l.isValid() } }
            validLabs?.let { labs ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = labs.filterNotNull(),
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.home(face),
                    centerPlain = true,
                )
            }
        }

    private fun buildHandoverFallbackCenters(
        handoff: ContinuousScanHandoff,
        saved: CalibrationCenters?,
    ): CalibrationCenters? {
        val merged = LinkedHashMap<Color, Lab>()
        // Prefer live continuous-scan centers over any older saved FR-24 calibration.
        handoff.calibration?.centers?.forEach { (c, lab) -> if (lab.isValid()) merged[c] = lab }
        if (handoff.globalAnchored) {
            handoff.faces.forEach { (face, fh) ->
                val color = Color.home(face)
                val lab = fh.labs.getOrNull(FaceSample.CENTER_INDEX)
                if (color !in merged && lab != null && lab.isValid()) merged[color] = lab
            }
        }
        saved?.centers?.forEach { (c, lab) -> if (c !in merged && lab.isValid()) merged[c] = lab }
        return if (merged.isEmpty()) null else CalibrationCenters(merged)
    }

    private fun handoffFaceReadings(
        face: Face,
        handoffFace: ContinuousFaceHandoff,
        calibration: CalibrationCenters? = null,
        classifyUnclassified: Boolean = false,
    ): List<StickerReading?> {
        val centers = if (classifyUnclassified) calibration?.takeIf(StickerClassifier::isValid)?.centers else null
        return List(STICKERS) { index ->
            if (index == FaceSample.CENTER_INDEX) {
                StickerReading(Color.home(face), handoffFace.confidence[index])
            } else {
                val color = handoffFace.colors[index]
                when {
                    color != null -> StickerReading(color, handoffFace.confidence[index])
                    centers != null -> handoffFace.labs[index]?.takeIf { it.isValid() }?.let { StickerClassifier.read(it, centers) }
                    else -> null
                }
            }
        }
    }

    /** A face held fixed in a retake: its [colors] (null stickers blank), or all blank but the center without any. */
    private fun fixedFace(
        face: Face,
        colors: List<Color?>?,
    ): List<StickerReading?> =
        List(STICKERS) { index ->
            val color = if (index == FaceSample.CENTER_INDEX) Color.home(face) else colors?.getOrNull(index)
            color?.let { StickerReading(it, StickerConfidence.CONFIDENT) }
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
            val a = grid[facets[0]]
            val b = grid[facets[1]]
            val c = grid[facets[2]]
            // A blank facet shows no cubie; never index the tables with it.
            pieces[slot] = if (a < 0 || b < 0 || c < 0) ILLEGAL else CORNER_CUBIE[a * FACES * FACES + b * FACES + c]
        }
        FaceletTables.EDGE_FACELETS.forEachIndexed { slot, facets ->
            val a = grid[facets[0]]
            val b = grid[facets[1]]
            val cubie = if (a < 0 || b < 0) ILLEGAL else EDGE_CUBIE[a * FACES + b]
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
    fun <T> rotateClockwise(
        cells: List<T>,
        turns: Int,
    ): List<T> = (0 until Math.floorMod(turns, TURNS)).fold(cells) { grid, _ -> CLOCKWISE.map { grid[it] } }
}
