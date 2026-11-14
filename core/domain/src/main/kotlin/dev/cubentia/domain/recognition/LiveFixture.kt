package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Face
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

@Serializable
data class LiveFixtureRecord(
    val schemaVersion: Int = 1,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val capturedAt: String,
    val calibration: CalibrationCenters,
    val labeledSample: LabeledFaceSample,
)

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
}

object LiveFixtureJson {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(record: LiveFixtureRecord): String = json.encodeToString(record)

    fun decode(value: String): LiveFixtureRecord =
        json.decodeFromString<LiveFixtureRecord>(value).also {
            require(it.schemaVersion == 1) { "Unsupported live fixture schema version: ${it.schemaVersion}" }
        }
}
