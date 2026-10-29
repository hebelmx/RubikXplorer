package dev.rubikxplorer.domain.cube

/** The six faces of the cube, in URFDLB order. A face Move names the Learner-facing face it turns. */
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
