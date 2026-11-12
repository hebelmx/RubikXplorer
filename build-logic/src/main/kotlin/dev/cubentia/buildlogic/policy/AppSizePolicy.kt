package dev.cubentia.buildlogic.policy

/** Conservative installed-size estimate based on docs/pruning-tables.md Story 3.8 evidence. */
object AppSizePolicy {
    const val BUDGET = 30_000_000L
    const val DATA_ALLOWANCE = 153_600L

    data class Verdict(
        val estimatedBytes: Long,
        val withinBudget: Boolean,
        val report: String,
    )

    fun evaluate(
        apkBytes: Long,
        dexBytes: Long,
        aabBytes: Long,
    ): Verdict {
        require(listOf(apkBytes, dexBytes, aabBytes).all { it >= 0 })
        val estimate = Math.addExact(Math.addExact(apkBytes, Math.multiplyExact(3L, dexBytes)), DATA_ALLOWANCE)
        return Verdict(
            estimate,
            estimate <= BUDGET,
            """
            Cubentia release installed-size estimate (bytes)
            AAB: $aabBytes
            Universal APK: $apkBytes
            Uncompressed dex: $dexBytes
            App-data allowance: $DATA_ALLOWANCE
            Installed estimate (APK + 3 x dex + data): $estimate
            Budget: $BUDGET
            Headroom: ${BUDGET - estimate}
            Estimate only; measured device method: docs/pruning-tables.md (Story 3.8).
            """.trimIndent() + "\n",
        )
    }
}
