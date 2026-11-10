package dev.cubentia.domain.lastlayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The four goals of the 2-look last layer. */
@Serializable
enum class LastLayerStep(
    val pieces: Int,
) {
    @SerialName("edges-oriented")
    EDGES_ORIENTED(4),

    @SerialName("oriented")
    ORIENTED(8),

    @SerialName("corners-permuted")
    CORNERS_PERMUTED(4),

    @SerialName("solved-up-to-auf")
    SOLVED_UP_TO_AUF(8),
}
