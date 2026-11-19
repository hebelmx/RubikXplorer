package dev.cubentia.domain.input

data class GestureInput(
    val paths: List<TouchPath>,
) {
    init {
        require(paths.size in 1..2)
    }
}

enum class PairKind { UNDECIDED, PARALLEL, TWIST, PINCH }
