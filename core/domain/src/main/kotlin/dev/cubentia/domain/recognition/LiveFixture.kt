package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Face
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val osRelease: String,
    val osBuild: String,
    val sdkInt: Int,
)

/** Where one sticker's truth came from: the operator set it, or it was a close-call-gated prefill the operator left. */
@Serializable
enum class TruthSource {
    @SerialName("entered")
    ENTERED,

    @SerialName("prefilled")
    PREFILLED,
}

/**
 * One recorded face. Schema 1 (Story 10.10) has no truth sources: every sticker was entered. Schema 2 (Story 10.12)
 * carries one [TruthSource] per sticker and optionally names a sibling [frame] image (e.g. `U.jpg`).
 */
@Serializable
data class LiveFixtureRecord(
    val schemaVersion: Int = LiveFixtureJson.CURRENT_SCHEMA,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val capturedAt: String,
    val calibration: CalibrationCenters,
    val labeledSample: LabeledFaceSample,
    val truthSources: List<TruthSource> = emptyList(),
    val frame: String? = null,
) {
    /** True when the operator entered sticker [index]; schema 1 records count every sticker as entered. */
    fun entered(index: Int): Boolean = schemaVersion == 1 || truthSources.getOrNull(index) == TruthSource.ENTERED
}

object LiveFixturePaths {
    fun directoryName(
        device: String,
        cube: String,
        light: String,
    ): String =
        listOf(device, cube, light).joinToString("_") { part ->
            part.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').also {
                require(it.isNotEmpty()) { "Fixture directory part must contain letters or digits: '$part'" }
            }
        }

    fun fileName(face: Face): String = "${face.name}.json"

    fun frameName(face: Face): String = "${face.name}.jpg"
}

object LiveFixtureJson {
    const val CURRENT_SCHEMA = 2
    private const val STICKERS = 9

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(record: LiveFixtureRecord): String = json.encodeToString(record)

    fun decode(value: String): LiveFixtureRecord =
        json.decodeFromString<LiveFixtureRecord>(value).also {
            when (it.schemaVersion) {
                1 -> {
                    require(it.truthSources.isEmpty() && it.frame == null) { "Schema 1 fixtures carry no truth sources or frame" }
                }

                CURRENT_SCHEMA -> {
                    require(it.truthSources.size == STICKERS) {
                        "Schema 2 fixtures need $STICKERS truth sources, found ${it.truthSources.size}"
                    }
                    require(it.frame == null || it.frame == LiveFixturePaths.frameName(it.labeledSample.sample.face)) {
                        "Frame must be ${LiveFixturePaths.frameName(it.labeledSample.sample.face)}, found ${it.frame}"
                    }
                }

                else -> {
                    throw IllegalArgumentException("Unsupported live fixture schema version: ${it.schemaVersion}")
                }
            }
        }
}

/** One corner capture with the three labeled, upright faces in hold order. */
@Serializable
data class CornerFixtureRecord(
    val schemaVersion: Int = CornerFixtureJson.CURRENT_SCHEMA,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val capturedAt: String,
    val calibration: CalibrationCenters,
    val hold: Int,
    val labeledSamples: List<LabeledFaceSample>,
    val truthSources: List<List<TruthSource>>,
    val frame: String = CornerFixturePaths.frameName(hold),
)

object CornerFixturePaths {
    fun directoryName(
        device: String,
        cube: String,
        light: String,
    ): String = LiveFixturePaths.directoryName(device, cube, light)

    fun fileName(hold: Int): String = "corner-$hold.json"

    fun frameName(hold: Int): String = "corner-$hold.jpg"
}

object CornerFixtureJson {
    const val CURRENT_SCHEMA = 3
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(record: CornerFixtureRecord): String = json.encodeToString(record)

    fun decode(value: String): CornerFixtureRecord =
        json.decodeFromString<CornerFixtureRecord>(value).also { record ->
            require(record.schemaVersion == CURRENT_SCHEMA) { "Unsupported corner fixture schema: ${record.schemaVersion}" }
            val plan = requireNotNull(QuickScanPlan.holds.getOrNull(record.hold - 1)) { "Unknown corner hold: ${record.hold}" }
            require(record.frame == CornerFixturePaths.frameName(record.hold))
            require(record.labeledSamples.size == plan.size && record.truthSources.size == plan.size)
            require(StickerClassifier.isValid(record.calibration))
            record.labeledSamples.zip(plan).forEach { (labeled, step) ->
                require(labeled.truth.size == 9 && labeled.truth[FaceSample.CENTER_INDEX] == step.centerColor)
                val sample = labeled.sample
                require(
                    sample.face == step.face && sample.centerColor == step.centerColor && sample.upNeighborColor == step.upNeighborColor,
                )
                require(sample.orientationDegrees == 0 && sample.stickers.size == 9 && sample.stickers.all { it.isValid() })
            }
            require(record.truthSources.all { it.size == 9 })
        }
}
