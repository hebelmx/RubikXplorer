package dev.rubikxplorer.solver

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Cubies

/**
 * Clean-room coordinate model from Herbert Kociemba's published mathematical description:
 * https://kociemba.org/math/coordlevel.htm and https://kociemba.org/math/twophase.htm.
 * No third-party solver implementation is used. Internal representatives are projections, not
 * validated cubes; in particular an isolated permutation need not have matching edge parity.
 */
internal enum class Coordinate(
    val size: Int,
) {
    TWIST(2187),
    FLIP(2048),
    SLICE_POSITION(495),
    CORNER_PERMUTATION(40320),
    EDGE_PERMUTATION(40320),
    SLICE_PERMUTATION(24),
    ;

    fun encode(cubies: Cubies): Int = encode(Representative.from(cubies))

    fun encode(c: Representative): Int =
        when (this) {
            TWIST -> {
                c.co.take(7).fold(0) { n, digit -> n * 3 + digit }
            }

            FLIP -> {
                c.eo.take(11).fold(0) { n, digit -> n * 2 + digit }
            }

            SLICE_POSITION -> {
                var rank = 0
                var remaining = 4
                for (slot in 11 downTo 0) {
                    if (c.ep[slot] >= 8) {
                        remaining--
                    } else if (remaining > 0) {
                        rank += choose(slot, remaining - 1)
                    }
                }
                rank
            }

            CORNER_PERMUTATION -> {
                rank(c.cp)
            }

            EDGE_PERMUTATION -> {
                require(c.ep.take(8).all { it < 8 })
                rank(c.ep.take(8))
            }

            SLICE_PERMUTATION -> {
                require(c.ep.drop(8).all { it >= 8 })
                rank(c.ep.drop(8).map { it - 8 })
            }
        }

    fun decode(value: Int): Representative {
        require(value in 0 until size)
        val solved = Representative.from(CubeState.SOLVED.cubies)
        return when (this) {
            TWIST -> {
                solved.copy(co = orientation(value, 8, 3))
            }

            FLIP -> {
                solved.copy(eo = orientation(value, 12, 2))
            }

            SLICE_POSITION -> {
                var rest = value
                var remaining = 4
                val occupied = BooleanArray(12)
                for (slot in 11 downTo 0) {
                    if (remaining == 0) break
                    val skipped = choose(slot, remaining - 1)
                    if (rest >= skipped) {
                        rest -= skipped
                    } else {
                        occupied[slot] = true
                        remaining--
                    }
                }
                var ordinary = 0
                var slice = 8
                solved.copy(ep = List(12) { if (occupied[it]) slice++ else ordinary++ })
            }

            CORNER_PERMUTATION -> {
                solved.copy(cp = unrank(value, 8))
            }

            EDGE_PERMUTATION -> {
                solved.copy(ep = unrank(value, 8) + (8..11))
            }

            SLICE_PERMUTATION -> {
                solved.copy(ep = (0..7).toList() + unrank(value, 4).map { it + 8 })
            }
        }
    }
}

/** Internal coordinate representative; deliberately cannot be converted into a domain CubeState. */
internal data class Representative(
    val cp: List<Int>,
    val co: List<Int>,
    val ep: List<Int>,
    val eo: List<Int>,
) {
    fun apply(move: Cubies): Representative =
        Representative(
            List(8) { cp[move.cornerPermutation[it]] },
            List(8) { (co[move.cornerPermutation[it]] + move.cornerOrientation[it]) % 3 },
            List(12) { ep[move.edgePermutation[it]] },
            List(12) { (eo[move.edgePermutation[it]] + move.edgeOrientation[it]) % 2 },
        )

    companion object {
        fun from(c: Cubies): Representative =
            Representative(
                c.cornerPermutation,
                c.cornerOrientation,
                c.edgePermutation,
                c.edgeOrientation,
            )
    }
}

private fun orientation(
    value: Int,
    count: Int,
    base: Int,
): List<Int> {
    var rest = value
    val digits = MutableList(count) { 0 }
    for (i in count - 2 downTo 0) {
        digits[i] = rest % base
        rest /= base
    }
    digits[count - 1] = (base - digits.sum() % base) % base
    return digits
}

// Inversion digits weighted by i!: the published natural ordering, with identity rank zero.
private fun rank(values: List<Int>): Int {
    var result = 0
    var factorial = 1
    for (i in 1 until values.size) {
        factorial *= i
        result += (0 until i).count { values[it] > values[i] } * factorial
    }
    return result
}

private fun unrank(
    value: Int,
    count: Int,
): List<Int> {
    var rest = value
    val digits = IntArray(count)
    for (i in 1 until count) {
        digits[i] = rest % (i + 1)
        rest /= i + 1
    }
    val available = (0 until count).toMutableList()
    val result = MutableList(count) { 0 }
    for (i in count - 1 downTo 0) result[i] = available.removeAt(i - digits[i])
    return result
}

private fun choose(
    n: Int,
    k: Int,
): Int {
    if (k < 0 || k > n) return 0
    var result = 1
    for (i in 1..k) result = result * (n - i + 1) / i
    return result
}
