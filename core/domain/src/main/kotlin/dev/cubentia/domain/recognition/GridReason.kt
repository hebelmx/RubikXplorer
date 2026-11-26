package dev.cubentia.domain.recognition

/** Why the hand-over sent a face to the Guided grid ([QuickScanClassification.decide]'s first failing rule). */
enum class GridReason(
    val label: String,
) {
    MISSING("missing"),
    QUALITY("quality"),
    UNCALIBRATED("uncalibrated"),
    HARD_DISAGREEMENT("hard-disagreement"),
    CENTRE_UNCERTAIN("centre-uncertain"),
    UNCERTAIN("uncertain"),

    /** Kept by the gate, but involved in an ambiguous or no-fit assembly ([QuickScanClassification.involvedFaces]). */
    REPROMPT("reprompt"),
}
