package dev.cubentia.domain.recognition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** One analysis frame of a recorder burst: what the corner reader saw and the camera's exposure for it. */
internal data class BurstFrame(
    val tMs: Long,
    val detected: Boolean,
    /** The twelve quad points in analysis-frame pixels; null when no cube was detected. */
    val outline: List<OutlinePoint>?,
    val faces: List<FrameStats?>,
    val aeState: Int?,
    val exposureNs: Long?,
    val iso: Int?,
)

/**
 * The recorder bursts of the 2026-11-22 Quick Scan bench session (`docs/evidence/quick-scan-2026-11-22/bursts/`), read
 * for the Story 10.22 replay tests: two with tablet and cube resting, two hand-held and one moved quickly. Numbers
 * only; the bursts hold no pixels.
 */
internal object RecordedBursts {
    const val STILL = "20261122-114155-still"
    const val STILL_LONG = "20261122-114230-still-long"
    const val HANDHELD = "20261122-115658-handheld"
    const val HANDHELD_2 = "20261122-155528-handheld-2"
    const val FAST = "20261122-155720-fast"

    val all = listOf(STILL, STILL_LONG, HANDHELD, HANDHELD_2, FAST)

    fun load(name: String): List<BurstFrame> =
        File("../../docs/evidence/quick-scan-2026-11-22/bursts/$name.jsonl")
            .readLines()
            .filter { it.isNotBlank() }
            .map { frame(Json.parseToJsonElement(it).jsonObject) }

    private fun frame(json: JsonObject): BurstFrame {
        val detected = json.getValue("detected").jsonPrimitive.boolean
        val points =
            json.getValue("quads").jsonArray.flatMap { quad ->
                quad.jsonArray.map { OutlinePoint(it.jsonArray[0].jsonPrimitive.double, it.jsonArray[1].jsonPrimitive.double) }
            }
        val faces = json.getValue("faces").jsonArray.map(::face)
        val exposure = json["exposure"].takeUnless { it == null || it is JsonNull }?.jsonObject
        return BurstFrame(
            tMs = json.getValue("tMs").jsonPrimitive.long,
            detected = detected,
            outline = points.takeIf { detected && it.isNotEmpty() },
            faces = if (faces.size == 3) faces else List(3) { null },
            aeState = exposure?.number("ae")?.jsonPrimitive?.int,
            exposureNs = exposure?.number("exposureNs")?.jsonPrimitive?.long,
            iso = exposure?.number("iso")?.jsonPrimitive?.int,
        )
    }

    private fun JsonObject.number(key: String): JsonElement? = this[key].takeUnless { it == null || it is JsonNull }

    private fun face(json: JsonElement): FrameStats? {
        if (json is JsonNull) return null
        val face = json.jsonObject
        val cells =
            face.getValue("cells").jsonArray.map { cell ->
                (cell as? JsonArray)?.let {
                    CellReading(
                        Lab(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double),
                        it[3].jsonPrimitive.int == 1,
                    )
                }
            }
        return FrameStats(
            cells = cells,
            meanLuma = face.getValue("meanLuma").jsonPrimitive.double,
            glareFraction = face.getValue("glare").jsonPrimitive.double,
            sharpness = face.getValue("sharpness").jsonPrimitive.double,
            seams = 12,
        )
    }
}
