package dev.cubentia.domain.recognition.evaluation.sticker

import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.QuickScanClassification
import dev.cubentia.domain.recognition.StickerReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Story 10.23: the per-sticker Kotlin reads of every face of the held-out groups named in `tools/sticker-model/holdout.json`,
 * for `tools/sticker-model/evaluate.py` to set beside the shadow sticker reader, through the existing
 * [QuickScanClassification] functions with the group's recorded calibration as the saved one. A corner group is judged as
 * by the original two-corner capture: hold 1's faces with only hold 1 captured, hold 2's with both
 * holds merged. A live group (not a Quick Scan) is judged with its six faces merged.
 * - `self`: the self-calibration read (the group's own face centers);
 * - `second`: the per-face correction against the record's `calibration.centers` (`cal`);
 * - `gated`: the agreement gate of the two ([QuickScanClassification.read]), what Quick Scan decides with.
 *
 * Item ids match `tools/sticker-model/assemble.py`: `live/<dir>/<FACE>` and `corner/<dir>/corner-<hold>/<FACE>`.
 */
object KotlinStickerReads {
    data class Face(
        val item: String,
        val group: String,
        val labeled: LabeledFaceSample,
        /** The corner hold that captured it; null for a live face. */
        val hold: Int? = null,
    )

    /** The faces of [group] (`live/<dir>` or `corner/<dir>`) with their recorded calibration; empty for any other source. */
    fun load(
        fixtures: File,
        group: String,
    ): Pair<List<Face>, CalibrationCenters?> {
        val (kind, name) = group.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val directory = File(File(fixtures, kind), name)
        return when (kind) {
            "live" -> {
                val records =
                    directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }.map {
                        LiveFixtureJson.decode(it.readText())
                    }
                records.map { record ->
                    val truth = record.labeledSample.truth
                    val sample = record.labeledSample.sample.copy(centerColor = truth[FaceSample.CENTER_INDEX])
                    Face("$group/${sample.face}", group, LabeledFaceSample(sample, truth))
                } to records.firstNotNullOfOrNull { it.calibration }
            }

            "corner" -> {
                val files = directory.listFiles { file -> file.name.matches(Regex("corner-\\d+\\.json")) }.orEmpty().sortedBy { it.name }
                val records = files.map { it.nameWithoutExtension to CornerFixtureJson.decode(it.readText()) }
                records.flatMap { (stem, record) ->
                    record.labeledSamples.map { labeled -> Face("$group/$stem/${labeled.sample.face}", group, labeled, record.hold) }
                } to records.firstNotNullOfOrNull { it.second.calibration }
            }

            else -> {
                emptyList<Face>() to null
            }
        }
    }

    /** One JSON object per face: truth, self, second (and its source), gated. */
    fun reads(
        faces: List<Face>,
        saved: CalibrationCenters?,
    ): List<JsonObject> {
        // The faces captured when [face] was judged: up to its hold for a corner face, all for a live one.
        fun context(face: Face): List<FaceSample> =
            faces.filter { other -> face.hold == null || (other.hold != null && other.hold <= face.hold) }.map { it.labeled.sample }
        return faces.map { face ->
            val sample = face.labeled.sample
            val merged = context(face)
            val report = QuickScanClassification.report(merged, listOf(sample.face), saved).single()
            JsonObject(
                mapOf(
                    "item" to JsonPrimitive(face.item),
                    "group" to JsonPrimitive(face.group),
                    "truth" to JsonArray(face.labeled.truth.map { JsonPrimitive(it.name) }),
                    "self" to readings(report.self),
                    "second" to readings(report.second),
                    "secondRead" to
                        JsonPrimitive(
                            when (report.secondRead) {
                                QuickScanClassification.SecondRead.CALIBRATION -> "cal"
                                QuickScanClassification.SecondRead.NORMALIZED -> "norm"
                                null -> "none"
                            },
                        ),
                    "gated" to readings(QuickScanClassification.read(sample, merged, saved)),
                ),
            )
        }
    }

    fun export(
        holdout: File,
        fixtures: File,
    ): String {
        val groups =
            Json
                .parseToJsonElement(holdout.readText())
                .let { (it as JsonObject).getValue("heldout").jsonArray }
                .map { it.jsonPrimitive.content }
                .sorted()
        val items =
            groups.flatMap { group ->
                val (faces, saved) = load(fixtures, group)
                require(faces.isNotEmpty()) { "No Kotlin-readable faces for held-out group $group" }
                reads(faces, saved)
            }
        val document =
            JsonObject(
                mapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "note" to
                        JsonPrimitive(
                            "Generated by ./gradlew :core:domain:exportKotlinStickerReads (Story 10.23). Lab values are the " +
                                "records' device measurements; self = self-calibration read, second = per-face correction against " +
                                "the record's calibration (cal), gated = QuickScanClassification.read.",
                        ),
                    "groups" to JsonArray(groups.map(::JsonPrimitive)),
                    "items" to JsonArray(items.sortedBy { (it.getValue("item") as JsonPrimitive).content }),
                ),
            )
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), document) + "\n"
    }

    private fun readings(values: List<StickerReading?>): JsonArray =
        JsonArray(
            values.map { reading ->
                reading?.let {
                    JsonObject(mapOf("color" to JsonPrimitive(it.color.name), "confidence" to JsonPrimitive(it.confidence.name)))
                } ?: JsonNull
            },
        )
}
