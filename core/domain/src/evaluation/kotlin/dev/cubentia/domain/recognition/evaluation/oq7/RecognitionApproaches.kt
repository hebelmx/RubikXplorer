package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab

data class ScoredSticker(
    val variant: Variant,
    val group: GroupObservation,
    val face: Face,
    val index: Int,
    val truth: Color,
    val prediction: Prediction,
) {
    val correct: Boolean get() = prediction.color == truth
    val confidentlyWrong: Boolean get() = !correct && prediction.confidence == Confidence.CONFIDENT
}

/** A group a variant did not score, and why (e.g. no frames, or no usable self-calibration). */
data class GroupNote(
    val group: String,
    val reason: String,
)

data class VariantRun(
    val variant: Variant,
    val stickers: List<ScoredSticker>,
    val notes: List<GroupNote>,
) {
    fun reading(
        group: String,
        face: Face,
        index: Int,
    ): ScoredSticker? = stickers.firstOrNull { it.group.name == group && it.face == face && it.index == index }
}

data class Metrics(
    val classified: Int,
    val correct: Int,
    val confidentlyWrong: Int,
    val uncertain: Int,
) {
    val accuracy: Double get() = if (classified == 0) 0.0 else correct.toDouble() / classified
    val confidentlyWrongRate: Double get() = if (classified == 0) 0.0 else confidentlyWrong.toDouble() / classified
    val meetsConfidentlyWrongTarget: Boolean get() = confidentlyWrongRate <= CONFIDENTLY_WRONG_TARGET

    companion object {
        /** NFR target: at most 0.1% of stickers both misread and marked confident. */
        const val CONFIDENTLY_WRONG_TARGET = 0.001

        fun of(stickers: List<ScoredSticker>) =
            Metrics(
                classified = stickers.size,
                correct = stickers.count { it.correct },
                confidentlyWrong = stickers.count { it.confidentlyWrong },
                uncertain = stickers.count { it.prediction.confidence == Confidence.UNCERTAIN },
            )
    }
}

/** The five OQ-7 variants over the same observations. */
object RecognitionApproaches {
    const val NO_FRAMES = "no frames"

    /** `K-cal`: nearest center on the JSON samples with each record's calibration, optionally [transform]ed. */
    fun kCal(
        groups: List<GroupObservation>,
        transform: (Map<Color, Lab>) -> Map<Color, Lab> = { it },
    ): VariantRun =
        VariantRun(
            Variant.K_CAL,
            groups.flatMap { group ->
                group.faces.flatMap { face ->
                    val centers = transform(face.record.calibration.centers)
                    score(Variant.K_CAL, group, face) { index -> NearestCenter.predict(face.samples[index], centers) }
                }
            },
            emptyList(),
        )

    /** `K-self`: nearest center on the JSON samples, calibrated by the group's six face-center samples. */
    fun kSelf(groups: List<GroupObservation>): VariantRun = selfCalibrated(Variant.K_SELF, groups) { face, index -> face.samples[index] }

    /** `O-cal`: nearest center on the frame cells' histogram modes with each record's calibration. */
    fun oCal(
        groups: List<GroupObservation>,
        transform: (Map<Color, Lab>) -> Map<Color, Lab> = { it },
    ): VariantRun {
        val notes = groups.filterNot { it.hasFrames }.map { GroupNote(it.name, NO_FRAMES) }
        val stickers =
            groups.filter { it.hasFrames }.flatMap { group ->
                group.faces.flatMap { face ->
                    val centers = transform(face.record.calibration.centers)
                    val cells = requireNotNull(face.cells)
                    score(Variant.O_CAL, group, face) { index -> NearestCenter.predict(cells[index].mode, centers) }
                }
            }
        return VariantRun(Variant.O_CAL, stickers, notes)
    }

    /** `O-self`: nearest center on the histogram modes, calibrated by the group's six center-cell modes. */
    fun oSelf(groups: List<GroupObservation>): VariantRun {
        val framed = groups.filter { it.hasFrames }
        val run = selfCalibrated(Variant.O_SELF, framed) { face, index -> requireNotNull(face.cells)[index].mode }
        return run.copy(notes = groups.filterNot { it.hasFrames }.map { GroupNote(it.name, NO_FRAMES) } + run.notes)
    }

    /** `M-loco`: the tiny MLP on the JSON samples, each cube predicted by the model trained without it. */
    fun mLoco(groups: List<GroupObservation>): Pair<VariantRun, LeaveOneCubeOut> {
        val positions =
            groups.flatMap { group ->
                group.faces.flatMap { face -> (0 until face.samples.size).map { index -> Triple(group, face, index) } }
            }
        val loco =
            LeaveOneCubeOutTraining.run(
                positions.map { (group, face, index) ->
                    LabeledLab(group.cube, face.samples[index], face.truth[index])
                },
            )
        val stickers =
            positions.zip(loco.predictions).map { (position, prediction) ->
                val (group, face, index) = position
                ScoredSticker(Variant.M_LOCO, group, face.face, index, face.truth[index], prediction)
            }
        return VariantRun(Variant.M_LOCO, stickers, emptyList()) to loco
    }

    private fun selfCalibrated(
        variant: Variant,
        groups: List<GroupObservation>,
        value: (FaceObservation, Int) -> Lab,
    ): VariantRun {
        val notes = mutableListOf<GroupNote>()
        val stickers =
            groups.flatMap { group ->
                val readings = group.faces.map { CenterReading(it.face, it.centerColor, value(it, FaceSample.CENTER_INDEX)) }
                when (val calibration = Calibrations.self(readings)) {
                    is SelfCalibration.Excluded -> {
                        notes += GroupNote(group.name, calibration.reason)
                        emptyList()
                    }

                    is SelfCalibration.Centers -> {
                        group.faces.flatMap { face ->
                            score(variant, group, face) { index -> NearestCenter.predict(value(face, index), calibration.centers) }
                        }
                    }
                }
            }
        return VariantRun(variant, stickers, notes)
    }

    private fun score(
        variant: Variant,
        group: GroupObservation,
        face: FaceObservation,
        predict: (Int) -> Prediction,
    ): List<ScoredSticker> =
        face.truth.indices.map { index ->
            ScoredSticker(variant, group, face.face, index, face.truth[index], predict(index))
        }
}
