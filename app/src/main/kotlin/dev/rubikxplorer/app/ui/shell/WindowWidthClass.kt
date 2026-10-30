package dev.rubikxplorer.app.ui.shell

/** The window width classes the layout is keyed on (Material breakpoints: 600dp, 840dp). */
enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        private const val MEDIUM_FROM_DP = 600f
        private const val EXPANDED_FROM_DP = 840f

        /** The class of a window [widthDp] wide. */
        fun of(widthDp: Float): WindowWidthClass =
            when {
                widthDp < MEDIUM_FROM_DP -> COMPACT
                widthDp < EXPANDED_FROM_DP -> MEDIUM
                else -> EXPANDED
            }
    }
}
