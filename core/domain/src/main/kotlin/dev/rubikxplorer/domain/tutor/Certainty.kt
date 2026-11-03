package dev.rubikxplorer.domain.tutor

/**
 * How sure the tutor is of what the evidence shows (AD-5): never a number. Only `tutor/Assess`
 * produces a value; everyone else may only match on one. `CertaintyProducerTest` enforces it.
 */
enum class Certainty {
    /** Exactly one candidate explains the evidence (and, for physical evidence, every face was seen). */
    VERIFIED,

    /** More than one candidate explains the evidence. */
    LIKELY,

    /** No candidate within reach explains the evidence. */
    UNKNOWN,
}
