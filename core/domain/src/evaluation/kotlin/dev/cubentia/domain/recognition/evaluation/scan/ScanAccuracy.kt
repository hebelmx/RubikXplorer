package dev.cubentia.domain.recognition.evaluation.scan

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.evaluation.FixtureGroup
import java.util.Random

/** Where a scan's nine values per face come from. */
enum class Input(
    val label: String,
) {
    /** A2 frames through [FrameReader] (ImageIO + sRGB → Lab + `CellReader`); session A has no frames. */
    KOTLIN_READER("Kotlin reader"),

    /** The record's own JSON samples (the recorder's center pixel per cell); `centerPlain` is not measured. */
    CENTER_PIXEL("Center pixel"),
}

enum class Session(
    val label: String,
) {
    A("Session A"),
    A2("Session A2"),
}

/** A reference device row; [model] is `Build.MODEL` as the recorder writes it. */
data class Device(
    val name: String,
    val model: String,
)

data class ScoredSticker(
    val face: Face,
    val index: Int,
    val truth: Color,
    val read: Color,
    val confidence: StickerConfidence,
) {
    val correct: Boolean get() = read == truth
    val confidentlyWrong: Boolean get() = !correct && confidence == StickerConfidence.CONFIDENT
}

/** One group scanned from one [input]: the self-calibration, the assembled result and its stickers against truth. */
data class ScanRun(
    val group: String,
    val session: Session,
    val model: String,
    val input: Input,
    val pose: Pose,
    val calibration: SelfCalibration,
    val result: ScanResult?,
    val stickers: List<ScoredSticker>,
    /** The saved calibration passed to `SelfCalibration.from`; always null in this evaluation. */
    val saved: CalibrationCenters? = null,
) {
    val full: Boolean get() = result is ScanResult.Full
    val allCorrect: Boolean get() = full && stickers.size == STICKERS && stickers.all { it.correct }
    val valid: Boolean
        get() = (result as? ScanResult.Full)?.let { CubeValidator.validate(it.facelets) is ValidationResult.Valid } ?: false

    private companion object {
        const val STICKERS = 54
    }
}

data class GroupSummary(
    val name: String,
    val session: Session,
    val model: String,
    val faces: Int,
    val framedFaces: Int,
    val stickers: Int,
    val pose: Pose,
)

data class ScanMetrics(
    val scans: Int,
    val full: Int,
    val allCorrect: Int,
    val fullValid: Int,
    val stickers: Int,
    val correct: Int,
    val confidentlyWrong: Int,
    val uncertain: Int,
) {
    val scanAccuracy: Double get() = if (scans == 0) 0.0 else allCorrect.toDouble() / scans
    val stickerAccuracy: Double get() = if (stickers == 0) 0.0 else correct.toDouble() / stickers
    val confidentlyWrongRate: Double get() = if (stickers == 0) 0.0 else confidentlyWrong.toDouble() / stickers
    val meetsTarget: Boolean get() = stickers > 0 && confidentlyWrongRate <= CONFIDENTLY_WRONG_TARGET

    companion object {
        /** NFR target: at most 0.1% of stickers both misread and marked confident. */
        const val CONFIDENTLY_WRONG_TARGET = 0.001

        fun of(runs: List<ScanRun>): ScanMetrics {
            val stickers = runs.flatMap { it.stickers }
            return ScanMetrics(
                scans = runs.size,
                full = runs.count { it.full },
                allCorrect = runs.count { it.allCorrect },
                fullValid = runs.count { it.valid },
                stickers = stickers.size,
                correct = stickers.count { it.correct },
                confidentlyWrong = stickers.count { it.confidentlyWrong },
                uncertain = stickers.count { it.confidence == StickerConfidence.UNCERTAIN },
            )
        }
    }
}

/**
 * One [prompted] run scanned again in any rotation (Story 10.14): every face's stickers turned by a seeded random quarter
 * turn ([turns]) and no up-neighbor color ([samples]), so `ScanAssembler` must work out each turn. [result] is the
 * assembled read and [reprompt] the faces it would re-prompt.
 */
data class AnyRotationRun(
    val prompted: ScanRun,
    val turns: Map<Face, Int>,
    val samples: List<FaceSample>,
    val result: ScanResult?,
    val reprompt: List<Face>,
) {
    val sameFacelets: Boolean
        get() {
            val read = (result as? ScanResult.Full)?.facelets ?: return false
            return read == (prompted.result as? ScanResult.Full)?.facelets
        }
    val needsReprompt: Boolean get() = reprompt.isNotEmpty()
}

data class ScanAccuracyReport(
    val groups: List<GroupSummary>,
    val runs: List<ScanRun>,
    val anyRotation: List<AnyRotationRun> = emptyList(),
) {
    fun runs(input: Input): List<ScanRun> = runs.filter { it.input == input }

    fun anyRotation(input: Input): List<AnyRotationRun> = anyRotation.filter { it.prompted.input == input }
}

/**
 * The 10.1 pipeline on the live fixtures: per group and input, `SelfCalibration.from` (no saved calibration) →
 * `ScanAssembler.assemble` → compare against the truth assembled with the same pose. Each face gets
 * `centerColor = truth[4]` (the prompt) and the up-neighbor color recovered from truth by [TruthPose].
 */
object ScanAccuracy {
    /** Seed of the any-rotation quarter turns, fixed so the report is byte-stable. */
    const val ANY_ROTATION_SEED = 1014L

    private const val TURNS = 4

    val DEVICES =
        listOf(
            Device("Lenovo Tab M10 Plus 3rd Gen", "Lenovo TB128XU"),
            Device("Galaxy S24 FE", "SM-S721U1"),
            Device("Galaxy Tab S7 FE", "SM-T738U"),
        )

    private val HOME_FACE: Map<Color, Face> = Face.entries.associateBy { Color.home(it) }

    /** [frames] measures a record's frame (null when it has none). */
    fun build(
        groups: List<FixtureGroup>,
        frames: (FixtureGroup, LiveFixtureRecord) -> List<CellReading>?,
    ): ScanAccuracyReport {
        val summaries = mutableListOf<GroupSummary>()
        val runs = mutableListOf<Pair<ScanRun, List<FaceSample>>>()
        groups.filter { it.records.isNotEmpty() }.sortedBy { it.name }.forEach { group ->
            val session = if (group.records.all { it.schemaVersion == 1 }) Session.A else Session.A2
            val model =
                group.records
                    .first()
                    .device.model
            val bySlot = group.records.groupBy { HOME_FACE.getValue(it.labeledSample.truth[FaceSample.CENTER_INDEX]) }
            val truth =
                bySlot.filterValues { it.size == 1 }.mapValues {
                    it.value
                        .single()
                        .labeledSample.truth
                }
            val pose = TruthPose.recover(truth)
            val measured = group.records.associateWith { frames(group, it) }
            summaries +=
                GroupSummary(
                    group.name,
                    session,
                    model,
                    group.records.size,
                    measured.values.count { it != null },
                    group.records.sumOf { it.labeledSample.truth.size },
                    pose,
                )
            runs += run(group.name, session, model, Input.CENTER_PIXEL, pose, group.records.map { it to it.labeledSample.sample })
            val framed =
                group.records.mapNotNull { record ->
                    measured[record]?.let {
                        record to
                            framedSample(record.labeledSample.sample, it)
                    }
                }
            if (framed.isNotEmpty()) runs += run(group.name, session, model, Input.KOTLIN_READER, pose, framed)
        }
        val random = Random(ANY_ROTATION_SEED)
        val anyRotation = runs.map { (run, samples) -> anyRotation(run, samples, random) }
        return ScanAccuracyReport(summaries, runs.map { it.first }, anyRotation)
    }

    /** [samples] with every face's stickers turned by a random quarter turn and no up-neighbor color, assembled again. */
    private fun anyRotation(
        run: ScanRun,
        samples: List<FaceSample>,
        random: Random,
    ): AnyRotationRun {
        val turns = samples.associate { HOME_FACE.getValue(requireNotNull(it.centerColor)) to random.nextInt(TURNS) }
        val turned =
            samples.map { sample ->
                val slot = HOME_FACE.getValue(requireNotNull(sample.centerColor))
                sample.copy(stickers = TruthPose.rotateClockwise(sample.stickers, turns.getValue(slot)), upNeighborColor = null)
            }
        val calibration = (SelfCalibration.from(turned) as? SelfCalibration.Calibrated)?.calibration
        return AnyRotationRun(
            run,
            Face.entries.filter { it in turns }.associateWith { turns.getValue(it) },
            turned,
            calibration?.let { ScanAssembler.assemble(turned, it) },
            calibration?.let { ScanAssembler.repromptFaces(turned, it) }.orEmpty(),
        )
    }

    private fun framedSample(
        sample: FaceSample,
        cells: List<CellReading>,
    ): FaceSample =
        sample.copy(
            stickers = cells.map { it.value },
            centerPlain = cells.getOrNull(FaceSample.CENTER_INDEX)?.plain,
        )

    private fun run(
        group: String,
        session: Session,
        model: String,
        input: Input,
        pose: Pose,
        records: List<Pair<LiveFixtureRecord, FaceSample>>,
    ): Pair<ScanRun, List<FaceSample>> {
        val samples =
            records.map { (record, sample) ->
                val center = record.labeledSample.truth[FaceSample.CENTER_INDEX]
                sample.copy(centerColor = center, upNeighborColor = pose.upNeighbor(HOME_FACE.getValue(center)))
            }
        val saved: CalibrationCenters? = null
        val calibration = SelfCalibration.from(samples, saved)
        val result = (calibration as? SelfCalibration.Calibrated)?.let { ScanAssembler.assemble(samples, it.calibration) }
        val placed: Map<Face, Pair<List<Color>, List<StickerConfidence>>> =
            when (result) {
                is ScanResult.Full -> {
                    Face.entries.associateWith { face ->
                        val range = face.ordinal * 9 until face.ordinal * 9 + 9
                        result.facelets.colors.slice(range) to result.confidence.slice(range)
                    }
                }

                is ScanResult.Partial -> {
                    result.observation.faces.mapValues { (face, colors) -> colors to result.confidence.getValue(face) }
                }

                null -> {
                    emptyMap()
                }
            }
        val truthNet =
            records.associate { (record, sample) ->
                val slot = HOME_FACE.getValue(record.labeledSample.truth[FaceSample.CENTER_INDEX])
                slot to TruthPose.rotateClockwise(record.labeledSample.truth, sample.orientationDegrees / 90 + pose.turns.getValue(slot))
            }
        val stickers =
            Face.entries.filter { it in placed }.flatMap { face ->
                val (colors, confidence) = placed.getValue(face)
                val truth = truthNet.getValue(face)
                colors.indices.map { ScoredSticker(face, it, truth[it], colors[it], confidence[it]) }
            }
        return ScanRun(group, session, model, input, pose, calibration, result, stickers, saved) to samples
    }
}
