package dev.rubikxplorer.app.spike3d

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Turn
import kotlin.math.cos
import kotlin.math.sin

/**
 * OQ-3 spike, throwaway: a physical 27-cubie cube shared by both prototype renderers.
 *
 * Space is the viewer frame: +x is R, +y is U, +z is F. Each cubie keeps its home position, an
 * exact integer rotation and its stickers on local faces. [facelets] is read back from that
 * geometry (rotated sticker normal picks the face, rotated position picks row and column), never
 * from a submitted target. A turn animates by a temporary angle about the face axis; the committed
 * rotations change only in [commit], by an exact integer quarter or half turn.
 */
class SpikeCubeModel(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) {
    /** A sticker of [color] on the cubie's local face with outward normal ([nx], [ny], [nz]). */
    class Sticker(
        val nx: Int,
        val ny: Int,
        val nz: Int,
        var color: Color,
    )

    /** One cubie: home position, committed rotation (row-major 3×3) and stickers. */
    class Cubie(
        val homeX: Int,
        val homeY: Int,
        val homeZ: Int,
    ) {
        val rotation: IntArray = identity()
        val stickers: List<Sticker> =
            buildList {
                if (homeX != 0) add(Sticker(homeX, 0, 0, Color.WHITE))
                if (homeY != 0) add(Sticker(0, homeY, 0, Color.WHITE))
                if (homeZ != 0) add(Sticker(0, 0, homeZ, Color.WHITE))
            }

        internal fun position(): IntArray = rotation.times(homeX, homeY, homeZ)
    }

    private class ActiveTurn(
        val axis: IntArray,
        val layer: List<Cubie>,
        val degrees: Float,
    )

    val cubies: List<Cubie> =
        buildList {
            for (x in -1..1) for (y in -1..1) for (z in -1..1) add(Cubie(x, y, z))
        }

    private var turn: ActiveTurn? = null
    private var angle = 0f

    /** Bumped whenever a sticker color changes (a [reset]); renderers rebuild colors when it moves. */
    var colorVersion: Int = 0
        private set

    val isTurning: Boolean get() = turn != null

    init {
        reset(initial)
    }

    /** Every cubie home and unrotated, sticker colors taken from [facelets]. Cancels any turn. */
    fun reset(facelets: Facelets) {
        turn = null
        angle = 0f
        for (cubie in cubies) {
            identity().copyInto(cubie.rotation)
            for (sticker in cubie.stickers) {
                sticker.color = facelets.colors[faceletIndex(cubie.homeX, cubie.homeY, cubie.homeZ, sticker)]
            }
        }
        colorVersion++
    }

    /** Starts animating [move] at progress 0; the committed geometry is unchanged until [commit]. */
    fun beginTurn(move: FaceTurn) {
        check(turn == null) { "a turn is already in progress" }
        val axis = axisOf(move.face)
        val layer = cubies.filter { it.position().dot(axis) == 1 }
        val degrees =
            when (move.turn) {
                Turn.CLOCKWISE -> -QUARTER
                Turn.COUNTERCLOCKWISE -> QUARTER
                Turn.HALF -> -2 * QUARTER
            }
        turn = ActiveTurn(axis, layer, degrees)
        angle = 0f
    }

    /** Sets the animation to [progress] in 0..1 of the current turn. */
    fun setProgress(progress: Float) {
        val active = turn ?: return
        angle = active.degrees * progress.coerceIn(0f, 1f)
    }

    /** Ends the current turn: the layer's rotations snap by the exact integer turn. */
    fun commit() {
        val active = turn ?: return
        val quarters = Math.round(active.degrees / QUARTER)
        val step = intRotation(active.axis, quarters)
        for (cubie in active.layer) step.times(cubie.rotation).copyInto(cubie.rotation)
        turn = null
        angle = 0f
    }

    /** The 54 facelets read from the committed cubie geometry. */
    fun facelets(): Facelets {
        val colors = arrayOfNulls<Color>(Facelets.SIZE)
        for (cubie in cubies) {
            val position = cubie.position()
            for (sticker in cubie.stickers) {
                val normal = cubie.rotation.times(sticker.nx, sticker.ny, sticker.nz)
                val index = faceletIndex(position[0], position[1], position[2], normal[0], normal[1], normal[2])
                check(colors[index] == null) { "two stickers read back at facelet $index" }
                colors[index] = sticker.color
            }
        }
        return Facelets(colors.map { checkNotNull(it) { "a facelet has no sticker" } })
    }

    /**
     * Writes cubie [index]'s model matrix (column-major 4×4, unit cubie spacing) into [out] at
     * [offset], including the in-progress animation angle for cubies in the turning layer.
     */
    fun modelMatrix(
        index: Int,
        out: FloatArray,
        offset: Int = 0,
    ) {
        val cubie = cubies[index]
        val active = turn
        val anim =
            if (active != null && cubie in active.layer) {
                val radians = Math.toRadians(angle.toDouble())
                floatRotation(active.axis, cos(radians).toFloat(), sin(radians).toFloat())
            } else {
                FLOAT_IDENTITY
            }
        val position = cubie.position()
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += anim[row * 3 + k] * cubie.rotation[k * 3 + col]
                out[offset + col * 4 + row] = sum
            }
            out[offset + col * 4 + 3] = 0f
        }
        for (row in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) sum += anim[row * 3 + k] * position[k]
            out[offset + 12 + row] = sum
        }
        out[offset + 15] = 1f
    }

    companion object {
        const val CUBIE_COUNT = 27
        private const val QUARTER = 90f
        private val FLOAT_IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** Outward axis of [face] in the viewer frame. */
        fun axisOf(face: Face): IntArray =
            when (face) {
                Face.U -> intArrayOf(0, 1, 0)
                Face.D -> intArrayOf(0, -1, 0)
                Face.R -> intArrayOf(1, 0, 0)
                Face.L -> intArrayOf(-1, 0, 0)
                Face.F -> intArrayOf(0, 0, 1)
                Face.B -> intArrayOf(0, 0, -1)
            }

        private fun faceletIndex(
            x: Int,
            y: Int,
            z: Int,
            sticker: Sticker,
        ) = faceletIndex(x, y, z, sticker.nx, sticker.ny, sticker.nz)

        /** Kociemba-net index of the sticker at position ([x], [y], [z]) facing ([nx], [ny], [nz]). */
        fun faceletIndex(
            x: Int,
            y: Int,
            z: Int,
            nx: Int,
            ny: Int,
            nz: Int,
        ): Int {
            val (face, row, col) =
                when {
                    ny == 1 -> Triple(Face.U, z + 1, x + 1)
                    ny == -1 -> Triple(Face.D, 1 - z, x + 1)
                    nz == 1 -> Triple(Face.F, 1 - y, x + 1)
                    nz == -1 -> Triple(Face.B, 1 - y, 1 - x)
                    nx == 1 -> Triple(Face.R, 1 - y, 1 - z)
                    else -> Triple(Face.L, 1 - y, z + 1)
                }
            return face.ordinal * 9 + row * 3 + col
        }

        private fun identity() = intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1)

        /** Rodrigues for a unit axis: R = cI + s[a]× + (1 − c)aaᵀ, row-major. */
        private fun floatRotation(
            a: IntArray,
            c: Float,
            s: Float,
        ): FloatArray {
            val (x, y, z) = Triple(a[0].toFloat(), a[1].toFloat(), a[2].toFloat())
            val t = 1f - c
            return floatArrayOf(
                c + t * x * x,
                t * x * y - s * z,
                t * x * z + s * y,
                t * x * y + s * z,
                c + t * y * y,
                t * y * z - s * x,
                t * x * z - s * y,
                t * y * z + s * x,
                c + t * z * z,
            )
        }

        /** The exact integer rotation by [quarters] × 90° (counter-clockwise positive) about [a]. */
        private fun intRotation(
            a: IntArray,
            quarters: Int,
        ): IntArray {
            val q = Math.floorMod(quarters, 4)
            val c = intArrayOf(1, 0, -1, 0)[q]
            val s = intArrayOf(0, 1, 0, -1)[q]
            val (x, y, z) = Triple(a[0], a[1], a[2])
            val t = 1 - c
            return intArrayOf(
                c + t * x * x,
                t * x * y - s * z,
                t * x * z + s * y,
                t * x * y + s * z,
                c + t * y * y,
                t * y * z - s * x,
                t * x * z - s * y,
                t * y * z + s * x,
                c + t * z * z,
            )
        }

        private fun IntArray.times(
            x: Int,
            y: Int,
            z: Int,
        ) = intArrayOf(
            this[0] * x + this[1] * y + this[2] * z,
            this[3] * x + this[4] * y + this[5] * z,
            this[6] * x + this[7] * y + this[8] * z,
        )

        private fun IntArray.times(other: IntArray): IntArray =
            IntArray(9) { i ->
                val row = i / 3
                val col = i % 3
                (0 until 3).sumOf { k -> this[row * 3 + k] * other[k * 3 + col] }
            }

        private fun IntArray.dot(other: IntArray) = this[0] * other[0] + this[1] * other[1] + this[2] * other[2]
    }
}
