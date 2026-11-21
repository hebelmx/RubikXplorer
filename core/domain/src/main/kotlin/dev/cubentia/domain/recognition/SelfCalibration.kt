package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color

/**
 * Calibration from the cube's own six face centers (OQ-7 decision K), with the logo-center guard.
 *
 * Each color's center is `stickers[4]` of the one sample whose [FaceSample.centerColor] is that color. A center whose
 * [FaceSample.centerPlain] is false (histogram mode share below 60%, e.g. a logo) is guarded and not used: it is replaced
 * by the [saved] calibration's value for that color when one is given; otherwise, when it is the only guarded color, it
 * is derived from the 8 non-center stickers (across the six samples) farthest from their nearest trusted center. A
 * color with no usable sample (none, more than one, not ACCEPTABLE, or malformed) is taken from [saved] when given.
 */
sealed interface SelfCalibration {
    /** Six centers in [Color.entries] order; [guarded] names the colors whose own center was not used. */
    data class Calibrated(
        val calibration: CalibrationCenters,
        val guarded: Set<Color>,
    ) : SelfCalibration

    /** These colors need the calibration flow (or a plain sticker) before a scan can be classified. */
    data class NeedsCalibration(
        val colors: Set<Color>,
    ) : SelfCalibration

    companion object {
        private const val DERIVED_STICKERS = 8

        fun from(
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
        ): SelfCalibration {
            val chosen = chosen(samples)
            val trusted = trusted(chosen)
            val guarded = chosen.filterValues { it.centerPlain == false }.keys
            val missing = Color.entries.toSet() - chosen.keys
            val replace = guarded + missing
            val filled: Map<Color, Lab> =
                when {
                    saved != null -> {
                        trusted + replace.mapNotNull { color -> saved.centers[color]?.takeIf { it.isValid() }?.let { color to it } }
                    }

                    missing.isEmpty() && guarded.size == 1 -> {
                        val usedSamples = samples.filter { sample -> chosen.values.any { it === sample } }
                        trusted + (guarded.single() to derive(usedSamples, trusted))
                    }

                    else -> {
                        trusted
                    }
                }
            val unresolved = Color.entries.filter { it !in filled }.toSet()
            if (unresolved.isNotEmpty()) return NeedsCalibration(unresolved)
            return Calibrated(CalibrationCenters(Color.entries.associateWith { filled.getValue(it) }), guarded)
        }

        /**
         * The colors whose own center [from] trusts (one usable sample, plain center), each with that center: the
         * capture's own partial calibration while some colors still have no sample.
         */
        fun trustedCenters(samples: List<FaceSample>): Map<Color, Lab> = trusted(chosen(samples))

        private fun chosen(samples: List<FaceSample>): Map<Color, FaceSample> =
            Color.entries
                .associateWith { color -> samples.filter { it.centerColor == color } }
                .filterValues { it.size == 1 && usable(it.single()) }
                .mapValues { it.value.single() }

        private fun trusted(chosen: Map<Color, FaceSample>): Map<Color, Lab> =
            chosen.filterValues { it.centerPlain != false }.mapValues { it.value.stickers[FaceSample.CENTER_INDEX] }

        private fun usable(sample: FaceSample): Boolean =
            sample.quality == CaptureQuality.ACCEPTABLE &&
                sample.stickers.size == ScanAssembler.STICKERS &&
                sample.stickers.all { it.isValid() }

        /** Mean of the non-center stickers farthest from their nearest trusted center (ties by sample order, then index). */
        private fun derive(
            samples: List<FaceSample>,
            trusted: Map<Color, Lab>,
        ): Lab {
            val farthest =
                samples
                    .flatMap { sample -> sample.stickers.filterIndexed { index, _ -> index != FaceSample.CENTER_INDEX } }
                    .map { sticker -> sticker to trusted.values.minOf { StickerClassifier.deltaE(sticker, it) } }
                    .sortedByDescending { it.second }
                    .take(DERIVED_STICKERS)
                    .map { it.first }
            return CellReader.mean(farthest)
        }
    }
}
