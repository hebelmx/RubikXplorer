package dev.rubikxplorer.application.session

/**
 * The 5 states of the physical-cube synchronization state machine (AD-3).
 */
enum class SyncState {
    VIRTUAL,
    PHYSICAL_AWAITING_CONFIRM,
    PHYSICAL_PREDICTED,
    PHYSICAL_VERIFIED,
    PHYSICAL_MISMATCH,
}
