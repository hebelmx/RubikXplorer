package dev.cubentia.domain.cube

import kotlinx.serialization.Serializable

/** The six faces of the cube, in URFDLB order. A face Move names the Learner-facing face it turns. */
@Serializable
enum class Face {
    U,
    R,
    F,
    D,
    L,
    B,
    ;

    /** The face on the other side of the cube. */
    val opposite: Face
        get() =
            when (this) {
                U -> D
                R -> L
                F -> B
                D -> U
                L -> R
                B -> F
            }
}
