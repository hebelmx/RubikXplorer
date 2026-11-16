package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets

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

/**
 * The only place samples are rotated into URFDLB order. Each sample goes to the face whose home color is its
 * [FaceSample.centerColor]; a sample without one is reported as NO_POSE on the [FaceSample.face] it was prompted for
 * when no other sample claims that face. Its stickers
 * turn from pixel order to the viewfinder by `orientationDegrees / 90` clockwise quarter turns, then to the Kociemba net
 * by the net direction of the up neighbor's face around that slot.
 */
object ScanAssembler {
    const val STICKERS = 9
    private const val QUARTER = 90
    private const val TURNS = 4

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

    fun assemble(
        samples: List<FaceSample>,
        calibration: CalibrationCenters,
    ): ScanResult {
        if (!StickerClassifier.isValid(calibration)) {
            return ScanResult.Partial(
                PhysicalObservation.Partial(emptyMap()),
                emptyMap(),
                Face.entries.associateWith { ScanGap.UNCALIBRATED },
            )
        }
        // Only a sample with a center color claims a slot. One without is reported (NO_POSE) on the face its prompt
        // named, and only when no sample claims that face.
        val bySlot =
            samples
                .mapNotNull { sample ->
                    sample.centerColor?.let { HOME_FACE.getValue(it) to sample }
                }.groupBy({ it.first }, { it.second })
        val unposed = samples.filter { it.centerColor == null }.map { it.face }.toSet()
        val placed = mutableMapOf<Face, List<StickerReading>>()
        val gaps = mutableMapOf<Face, ScanGap>()
        Face.entries.forEach { slot ->
            val claims = bySlot[slot].orEmpty()
            when (claims.size) {
                0 -> {
                    gaps[slot] = if (slot in unposed) ScanGap.NO_POSE else ScanGap.MISSING
                }

                1 -> {
                    when (val placement = place(slot, claims.single(), calibration.centers)) {
                        is Placement.Placed -> placed[slot] = placement.readings
                        is Placement.Gap -> gaps[slot] = placement.gap
                    }
                }

                else -> {
                    gaps[slot] = ScanGap.DUPLICATE
                }
            }
        }
        if (placed.size == Face.entries.size) {
            val readings = Face.entries.flatMap { placed.getValue(it) }
            return ScanResult.Full(Facelets(readings.map { it.color }), readings.map { it.confidence })
        }
        val faces = Face.entries.filter { it in placed }
        return ScanResult.Partial(
            PhysicalObservation.Partial(faces.associateWith { face -> placed.getValue(face).map { it.color } }),
            faces.associateWith { face -> placed.getValue(face).map { it.confidence } },
            Face.entries.filter { it in gaps }.associateWith { gaps.getValue(it) },
        )
    }

    private sealed interface Placement {
        data class Placed(
            val readings: List<StickerReading>,
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
        // Only samples whose center color is this slot's home color claim it.
        val center = Color.home(slot)
        val upFace = sample.upNeighborColor?.let(HOME_FACE::getValue)
        val direction = upFace?.let { NET_NEIGHBORS.getValue(slot).indexOf(it) } ?: -1
        if (direction < 0 || sample.orientationDegrees % QUARTER != 0) return Placement.Gap(ScanGap.NO_POSE)
        val readings =
            sample.stickers.mapIndexed { index, sticker ->
                val reading = StickerClassifier.read(sticker, centers)
                if (index != FaceSample.CENTER_INDEX) {
                    reading
                } else {
                    // The prompt fixes the center's color; it is CONFIDENT only if the read agrees confidently.
                    val agrees = reading.color == center && reading.confidence == StickerConfidence.CONFIDENT
                    StickerReading(center, if (agrees) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN)
                }
            }
        val turns = Math.floorMod(sample.orientationDegrees / QUARTER + direction, TURNS)
        return Placement.Placed(rotateClockwise(readings, turns))
    }

    /** [cells] (a row-major 3×3) turned [turns] clockwise quarter turns. */
    internal fun <T> rotateClockwise(
        cells: List<T>,
        turns: Int,
    ): List<T> = (0 until Math.floorMod(turns, TURNS)).fold(cells) { grid, _ -> CLOCKWISE.map { grid[it] } }
}
