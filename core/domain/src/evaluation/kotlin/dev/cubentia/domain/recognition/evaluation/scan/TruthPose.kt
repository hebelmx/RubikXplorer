package dev.cubentia.domain.recognition.evaluation.scan

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult

/** How the six truth faces of a group sit in the net: clockwise quarter turns per slot, and whether that pose was found. */
data class Pose(
    val turns: Map<Face, Int>,
    val status: Status,
) {
    enum class Status(
        val label: String,
    ) {
        UNIQUE("valid cube"),
        AMBIGUOUS("valid cube, several poses (first used)"),
        NOT_A_CUBE("truth is not a valid cube"),
    }

    /** The up-neighbor color a Guided Scan prompt would have named for [slot]. */
    fun upNeighbor(slot: Face): Color = Color.home(NET_NEIGHBORS.getValue(slot)[turns.getValue(slot)])

    companion object {
        /** Faces around each slot in the `Facelets` net, clockwise from the top (same table as `ScanAssembler`). */
        val NET_NEIGHBORS: Map<Face, List<Face>> =
            mapOf(
                Face.U to listOf(Face.B, Face.R, Face.F, Face.L),
                Face.D to listOf(Face.F, Face.R, Face.B, Face.L),
                Face.R to listOf(Face.U, Face.B, Face.D, Face.F),
                Face.F to listOf(Face.U, Face.R, Face.D, Face.L),
                Face.L to listOf(Face.U, Face.F, Face.D, Face.B),
                Face.B to listOf(Face.U, Face.L, Face.D, Face.R),
            )
    }
}

/**
 * Recovers the per-face pose from truth (an evaluation stand-in for the 10.2 prompt: the recorder never asked which side
 * was up). The pose is the per-slot quarter turns under which the six truth faces assemble into a `CubeValidator`-valid
 * cube; when none does, every turn is 0 and the group is flagged.
 */
object TruthPose {
    private const val TURNS = 4
    private val CLOCKWISE = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2)

    /** [truth] is keyed by slot (the face whose home color is the truth center), colors in pixel order. */
    fun recover(truth: Map<Face, List<Color>>): Pose {
        val zero = Face.entries.associateWith { 0 }
        if (truth.keys != Face.entries.toSet()) return Pose(zero, Pose.Status.NOT_A_CUBE)
        val valid = mutableListOf<Map<Face, Int>>()
        for (code in 0 until (1 shl (2 * Face.entries.size))) {
            val turns = Face.entries.associateWith { (code shr (2 * it.ordinal)) and (TURNS - 1) }
            if (CubeValidator.validate(assemble(truth, turns)) is ValidationResult.Valid) valid += turns
        }
        return when (valid.size) {
            0 -> Pose(zero, Pose.Status.NOT_A_CUBE)
            1 -> Pose(valid.single(), Pose.Status.UNIQUE)
            else -> Pose(valid.first(), Pose.Status.AMBIGUOUS)
        }
    }

    fun assemble(
        faces: Map<Face, List<Color>>,
        turns: Map<Face, Int>,
    ): Facelets = Facelets(Face.entries.flatMap { rotateClockwise(faces.getValue(it), turns.getValue(it)) })

    fun <T> rotateClockwise(
        cells: List<T>,
        turns: Int,
    ): List<T> = (0 until Math.floorMod(turns, TURNS)).fold(cells) { grid, _ -> CLOCKWISE.map { grid[it] } }
}
