package dev.cubentia.domain.recognition.evaluation

import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.NearestCenterClassifier

data class FixtureGroup(
    val name: String,
    val records: List<LiveFixtureRecord>,
)

data class BaselineScore(
    val name: String,
    val correct: Int,
    val stickers: Int,
    val correctFaces: Int,
    val classifiedFaces: Int,
    val unavailable: Int,
    val missing: List<Face>,
) {
    val confidentlyWrong: Int get() = stickers - correct
    val accuracy: Double get() = if (stickers == 0) 0.0 else correct.toDouble() / stickers
}

enum class BaselineGate { MET, MISSED, INCOMPLETE }

data class LiveBaseline(
    val groups: List<BaselineScore>,
    val devices: List<BaselineScore>,
    val total: BaselineScore,
    val gate: BaselineGate,
    val cubes: Int,
    val missingMatrix: List<String> = emptyList(),
)

object LiveBaselineEvaluator {
    fun evaluate(groups: List<FixtureGroup>): LiveBaseline {
        val scores = groups.map { score(it.name, it.records) }
        val devices =
            groups
                .flatMap { it.records }
                .groupBy { "${it.device.manufacturer} ${it.device.model}" }
                .toSortedMap()
                .map { (name, records) -> score(name, records) }
        val total = score("Total", groups.flatMap { it.records })
        val missingMatrix = missingMatrix(groups.flatMap { it.records })
        val gate =
            when {
                groups.isEmpty() || scores.any { it.missing.isNotEmpty() } || missingMatrix.isNotEmpty() -> BaselineGate.INCOMPLETE
                total.accuracy >= 0.99 -> BaselineGate.MET
                else -> BaselineGate.MISSED
            }
        val cubes =
            groups
                .flatMap { it.records }
                .map { it.cube.lowercase() }
                .distinct()
                .size
        return LiveBaseline(scores, devices, total, gate, cubes, missingMatrix)
    }

    private fun deviceName(record: LiveFixtureRecord) = "${record.device.manufacturer} ${record.device.model}"

    /**
     * Story 10.10 AC2 gate matrix: at least [REQUIRED_DEVICES] devices must each have all six faces classified for every
     * recorded cube, and at least [REQUIRED_CUBES] cubes must be recorded. Extra devices that do not cover every cube
     * (e.g. a report-only tablet) do not block the verdict. Returns a description of each missing device × cube cell,
     * or an empty list when the matrix is complete.
     */
    private fun missingMatrix(records: List<LiveFixtureRecord>): List<String> {
        val devices = records.map(::deviceName).distinct().sorted()
        val cubes = records.map { it.cube.lowercase() }.distinct().sorted()
        val incomplete =
            devices.flatMap { device ->
                cubes.mapNotNull { cube ->
                    val cell = records.filter { deviceName(it) == device && it.cube.lowercase() == cube }
                    val missing = score("$device × $cube", cell).missing
                    if (missing.isEmpty()) null else "$device × $cube (missing ${missing.joinToString { it.name }})"
                }
            }
        val completeDevices = devices.count { device -> incomplete.none { it.startsWith("$device × ") } }
        if (cubes.size >= REQUIRED_CUBES && completeDevices >= REQUIRED_DEVICES) return emptyList()
        return buildList {
            if (cubes.size <
                REQUIRED_CUBES
            ) {
                add("cubes: ${cubes.size} of $REQUIRED_CUBES recorded (${cubes.joinToString().ifEmpty { "none" }})")
            }
            if (completeDevices < REQUIRED_DEVICES) {
                add(
                    "devices covering every cube: $completeDevices of $REQUIRED_DEVICES (${devices.joinToString().ifEmpty {
                        "none"
                    }} recorded)",
                )
            }
            addAll(incomplete)
        }
    }

    private const val REQUIRED_DEVICES = 2
    private const val REQUIRED_CUBES = 2

    private fun score(
        name: String,
        records: List<LiveFixtureRecord>,
    ): BaselineScore {
        var correct = 0
        var stickers = 0
        var correctFaces = 0
        var classifiedFaces = 0
        var unavailable = 0
        val faces = mutableSetOf<Face>()
        records.forEach { record ->
            val labeled = record.labeledSample
            when (val result = NearestCenterClassifier.classify(labeled.sample, record.calibration, labeled.truth)) {
                is ClassificationResult.Unavailable -> {
                    unavailable++
                }

                is ClassificationResult.Classified -> {
                    correct += result.value.correct
                    stickers += result.value.total
                    classifiedFaces++
                    if (result.value.correct == result.value.total) correctFaces++
                    faces += labeled.sample.face
                }
            }
        }
        return BaselineScore(name, correct, stickers, correctFaces, classifiedFaces, unavailable, Face.entries.filter { it !in faces })
    }
}
