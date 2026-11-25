package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.text.Text

/** Neutral 2D point in image coordinates (X right, Y down). */
data class Point2D(
    val x: Double,
    val y: Double,
) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite()
}

/** Four clockwise image-space corners `[TL, TR, BR, BL]` of a projected cube face. */
data class Quad2D(
    val corners: List<Point2D>,
) {
    init {
        require(corners.size == CORNERS) { "Quad2D requires $CORNERS corners, got ${corners.size}" }
    }

    fun isFinite(): Boolean = corners.all { it.isFinite() }

    companion object {
        const val CORNERS = 4
    }
}

/** One projective 3×3 face lattice fitted from unordered 2D sticker-center detections. */
data class FittedFace(
    val quad: Quad2D,
    val score: Double,
    val inlierIndices: List<Int>,
    val gridCenters: List<Point2D>,
    val meanError: Double,
)

/** Neutral sRGB triplet with channel values in `[0.0, 255.0]`. */
data class RgbColor(
    val r: Double,
    val g: Double,
    val b: Double,
) {
    fun isFinite(): Boolean = r.isFinite() && g.isFinite() && b.isFinite()
}

/**
 * Neutral per-face observation crossing from the camera layer into `:core:domain` (AD-9).
 *
 * [quad] holds 4 clockwise corners; [cellRgbs] and [cellQualities] hold 9 row-major patches in
 * [quad]'s raw corner order (index 0 near corner 0, index 2 near corner 1, index 8 near corner 2,
 * index 6 near corner 3, index 4 center). When [ContinuousFaceletMapper] aligns [quad] by rolling
 * corners left by `shift`, it rotates [cellRgbs] and [cellQualities] counter-clockwise by `shift`
 * quarter turns.
 */
data class QuadObservation(
    val quad: Quad2D,
    val cellRgbs: List<RgbColor>,
    val cellQualities: List<Double>,
    val sharpness: Double,
    val hasGlare: Boolean,
    val score: Double = 1.0,
) {
    init {
        require(cellRgbs.size == CELLS) { "QuadObservation requires $CELLS cellRgbs, got ${cellRgbs.size}" }
        require(cellQualities.size == CELLS) { "QuadObservation requires $CELLS cellQualities, got ${cellQualities.size}" }
    }

    companion object {
        const val CELLS = 9
    }
}

/** A canonical or local sticker position `(face, cellIndex)` in row-major `0..8` order. */
data class FaceSlot(
    val face: Face,
    val cellIndex: Int,
) : Comparable<FaceSlot> {
    init {
        require(cellIndex in 0 until QuadObservation.CELLS) { "cellIndex out of bounds: $cellIndex" }
    }

    override fun compareTo(other: FaceSlot): Int = compareValuesBy(this, other, { it.face.ordinal }, { it.cellIndex })
}

/** One live tracked quad overlay for viewfinder rendering. */
data class TrackedQuadOverlay(
    /** Canonical `Orientation.HOME` face when anchored on master, else null. */
    val face: Face?,
    /** Local fragment face identifier. */
    val localFace: Face,
    /** Aligned clockwise quad in image coordinates. */
    val quad: Quad2D,
)

/** Sealed inline guidance cues emitted by [ContinuousFaceletMapper] (AD-12). */
sealed interface ContinuousScanCue {
    fun toText(): Text

    data object BringIntoView : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.bring-into-view")
    }

    data class TurnRemaining(
        val remainingSides: Int,
    ) : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.turn-remaining", mapOf("count" to remainingSides))
    }

    data object TiltAdjacent : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.tilt-adjacent")
    }

    data object KeepTurningRealign : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.keep-turning-realign")
    }

    data object TiltAwayLight : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.tilt-away-light")
    }

    data class TiltAwayLightRemaining(
        val remainingSides: Int,
    ) : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.tilt-away-light-remaining", mapOf("count" to remainingSides))
    }

    data class ResolveConflict(
        val face: Face,
    ) : ContinuousScanCue {
        override fun toText(): Text =
            Text(
                "msg.quick-scan.continuous.resolve-conflict",
                mapOf("color" to Color.home(face).name.lowercase()),
            )
    }

    data object LayerTwistDetected : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.layer-twist")
    }

    data object AllVerified : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.all-verified")
    }

    data class TiltRemainingStickers(
        val committedCount: Int,
    ) : ContinuousScanCue {
        override fun toText(): Text = Text("msg.quick-scan.continuous.tilt-remaining-stickers", mapOf("committed" to committedCount))
    }
}

/** Immutable per-frame snapshot returned by [ContinuousFaceletMapper.processFrame]. */
sealed interface ContinuousScanSnapshot {
    val frameIndex: Int
    val net: Map<Face, List<Color?>>
    val conflictedSlots: List<FaceSlot>
    val trackedQuads: List<TrackedQuadOverlay>
    val globalAnchored: Boolean
    val twistDetected: Boolean
    val cue: ContinuousScanCue
    val committedCount: Int
    val mappedFacesCount: Int
    val invariantViolationCount: Int
    val pendingFragmentFaces: Int

    data class Scanning(
        override val frameIndex: Int,
        override val net: Map<Face, List<Color?>>,
        override val conflictedSlots: List<FaceSlot>,
        override val trackedQuads: List<TrackedQuadOverlay>,
        override val globalAnchored: Boolean,
        override val cue: ContinuousScanCue,
        override val committedCount: Int,
        override val mappedFacesCount: Int,
        override val invariantViolationCount: Int,
        override val pendingFragmentFaces: Int,
    ) : ContinuousScanSnapshot {
        override val twistDetected: Boolean get() = false
    }

    data class TwistDetected(
        override val frameIndex: Int,
        val twistFrame: Int,
        override val net: Map<Face, List<Color?>>,
        override val conflictedSlots: List<FaceSlot>,
        override val trackedQuads: List<TrackedQuadOverlay>,
        override val globalAnchored: Boolean,
        override val cue: ContinuousScanCue,
        override val committedCount: Int,
        override val mappedFacesCount: Int,
        override val invariantViolationCount: Int,
        override val pendingFragmentFaces: Int,
    ) : ContinuousScanSnapshot {
        override val twistDetected: Boolean get() = true
    }

    data class Complete(
        override val frameIndex: Int,
        val state: CubeState,
        override val net: Map<Face, List<Color?>>,
        override val conflictedSlots: List<FaceSlot>,
        override val trackedQuads: List<TrackedQuadOverlay>,
        override val cue: ContinuousScanCue,
        override val committedCount: Int,
        override val mappedFacesCount: Int,
        override val pendingFragmentFaces: Int,
    ) : ContinuousScanSnapshot {
        override val globalAnchored: Boolean get() = true
        override val twistDetected: Boolean get() = false
        override val invariantViolationCount: Int get() = 0
    }
}

/**
 * One confirmed face exported from a continuous scan for Review, Guided Scan handover (Story 10.17),
 * or Solve-tab Editor seeding (#321).
 */
data class ContinuousFaceHandoff(
    val face: Face,
    val colors: List<Color?>,
    val confidence: List<StickerConfidence>,
    val labs: List<Lab?>,
    val orientationLocked: Boolean,
) {
    init {
        require(colors.size == QuadObservation.CELLS) { "colors must have 9 entries, got ${colors.size}" }
        require(confidence.size == QuadObservation.CELLS) { "confidence must have 9 entries, got ${confidence.size}" }
        require(labs.size == QuadObservation.CELLS) { "labs must have 9 entries, got ${labs.size}" }
    }
}

/**
 * Typed domain handoff payload from [ContinuousFaceletMapper].
 *
 * When [globalAnchored] is true, every entry in [faces] is canonicalized to `Orientation.HOME`
 * (`U=WHITE, F=GREEN, R=RED, D=YELLOW, L=ORANGE, B=BLUE`) and [calibration] carries the six
 * self-calibrated center Labs once all six centers have been observed.
 */
data class ContinuousScanHandoff(
    val faces: Map<Face, ContinuousFaceHandoff>,
    val calibration: CalibrationCenters? = null,
    val globalAnchored: Boolean = true,
) {
    /** True when all 6 canonical faces have 9 confident non-null stickers. */
    val isComplete: Boolean
        get() =
            globalAnchored &&
                faces.size == Face.entries.size &&
                Face.entries.all { f ->
                    val fh = faces[f] ?: return@all false
                    fh.colors.none { it == null } && fh.confidence.all { it == StickerConfidence.CONFIDENT }
                }

    /**
     * 54 URFDLB stickers for seeding the Solve-tab Manual Editor (#321): confirmed confident stickers
     * are set; unmapped, uncertain, or unanchored cells stay `null`.
     */
    fun toFallbackStickers(): List<Color?> {
        if (!globalAnchored || faces.isEmpty()) return List(Facelets.SIZE) { null }
        return List(Facelets.SIZE) { index ->
            val face = Face.entries[index / QuadObservation.CELLS]
            val cell = index % QuadObservation.CELLS
            val handoffFace = faces[face] ?: return@List null
            if (cell == CENTER_CELL) {
                handoffFace.colors[cell] ?: Color.home(face)
            } else {
                handoffFace.colors[cell].takeIf { handoffFace.confidence[cell] == StickerConfidence.CONFIDENT }
            }
        }
    }

    companion object {
        private const val CENTER_CELL = 4
    }
}

/** Exported outcome of a continuous scan session. */
sealed interface ContinuousScanExport {
    val handoff: ContinuousScanHandoff

    data class Complete(
        val state: CubeState,
        val facelets: Facelets,
        override val handoff: ContinuousScanHandoff,
    ) : ContinuousScanExport

    data class Partial(
        override val handoff: ContinuousScanHandoff,
    ) : ContinuousScanExport

    data class TwistDetected(
        val twistFrame: Int?,
        override val handoff: ContinuousScanHandoff,
    ) : ContinuousScanExport
}
