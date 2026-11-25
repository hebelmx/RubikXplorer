package dev.cubentia.app.camera.continuous

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * Story 10.31 (AC3), in CI: `tools/center-model/model-card.json` training manifest is strictly disjoint
 * from the canonical holdout union (`tools/sticker-model/holdout.json` ∪ held-out `Fixtures/corner/groups.json`),
 * excludes `live/sm-s721u1_stkb_studio-a`, uses zero `Fixtures/corner/` groups, and matches the committed model SHA-256.
 */
class StickerCenterModelSplitTest {
    private val repo = File("..")
    private val card = parse(File(repo, "tools/center-model/model-card.json"))
    private val modelBytes = File("src/camera/continuous/assets/${StickerCenterModelContract.MODEL_ASSET}").readBytes()
    private val holdoutUnion = loadCanonicalHoldoutUnion()

    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun loadCanonicalHoldoutUnion(): Set<String> {
        val held = linkedSetOf<String>()
        val holdoutFile = File(repo, "tools/sticker-model/holdout.json")
        require(holdoutFile.isFile) { "Missing canonical holdout file: ${holdoutFile.absolutePath}" }
        parse(holdoutFile).getValue("heldout").jsonArray.forEach { held += it.jsonPrimitive.content }
        val cornerGroups = File(repo, "Fixtures/corner/groups.json")
        if (cornerGroups.isFile) {
            parse(cornerGroups).getValue("groups").jsonArray.map { it.jsonObject }.forEach { entry ->
                if (entry.getValue("split").jsonPrimitive.content == "heldout") {
                    held += "corner/${entry.getValue("group").jsonPrimitive.content}"
                }
            }
        }
        return held
    }

    private fun validate(
        candidate: JsonObject,
        bytes: ByteArray,
    ): List<String> {
        val failures = mutableListOf<String>()
        val recordedSha = candidate.getValue("sha256").jsonPrimitive.content
        val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!Regex("^[0-9a-f]{64}$").matches(recordedSha)) failures += "invalid sha256 format: $recordedSha"
        if (actualSha != recordedSha) failures += "sha256 mismatch: actual $actualSha != recorded $recordedSha"

        val dataset = candidate.getValue("dataset").jsonObject
        val cardHoldout =
            dataset
                .getValue("holdoutUnion")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        val missingHoldout = (holdoutUnion - cardHoldout).sorted()
        if (missingHoldout.isNotEmpty()) failures += "holdoutUnion missing canonical groups: $missingHoldout"

        val trainLive = dataset.getValue("trainingLiveGroups").jsonArray.map { it.jsonPrimitive.content }
        val trainCorner = dataset.getValue("trainingCornerGroups").jsonArray.map { it.jsonPrimitive.content }
        val excluded =
            dataset
                .getValue("excludedLiveGroups")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

        if (EXCLUDED_LIVE_GROUP !in excluded) failures += "excludedLiveGroups missing $EXCLUDED_LIVE_GROUP"
        if (trainCorner.isNotEmpty()) failures += "trainingCornerGroups must be empty, got $trainCorner"

        for (group in trainLive + trainCorner) {
            if (group in holdoutUnion || group in cardHoldout) failures += "held-out group leaked into training: $group"
            if (group == EXCLUDED_LIVE_GROUP || group in excluded) failures += "excluded group leaked into training: $group"
            if (group.startsWith("corner/")) failures += "Fixtures/corner/ group leaked into training: $group"
        }
        return failures
    }

    @Test
    fun `committed model card passes holdout disjointness and provenance checks`() {
        assertTrue(holdoutUnion.isNotEmpty())
        assertEquals(emptyList<String>(), validate(card, modelBytes))
    }

    @Test
    fun `injecting a held-out group fails loudly`() {
        val leakedGroup = holdoutUnion.sorted().first()
        val dataset = card.getValue("dataset").jsonObject
        val badLive = JsonArray(dataset.getValue("trainingLiveGroups").jsonArray + JsonPrimitive(leakedGroup))
        val badCard = JsonObject(card + ("dataset" to JsonObject(dataset + ("trainingLiveGroups" to badLive))))
        val found = validate(badCard, modelBytes)
        assertTrue(found.any { leakedGroup in it }, found.toString())
    }

    @Test
    fun `injecting the excluded live group fails loudly`() {
        val dataset = card.getValue("dataset").jsonObject
        val badLive = JsonArray(dataset.getValue("trainingLiveGroups").jsonArray + JsonPrimitive(EXCLUDED_LIVE_GROUP))
        val badCard = JsonObject(card + ("dataset" to JsonObject(dataset + ("trainingLiveGroups" to badLive))))
        val found = validate(badCard, modelBytes)
        assertTrue(found.any { EXCLUDED_LIVE_GROUP in it }, found.toString())
    }

    @Test
    fun `injecting a corner fixture group fails loudly`() {
        val dataset = card.getValue("dataset").jsonObject
        val badCorner = JsonArray(listOf(JsonPrimitive("corner/lenovo-tb128xu_stka_bar-warm")))
        val badCard = JsonObject(card + ("dataset" to JsonObject(dataset + ("trainingCornerGroups" to badCorner))))
        val found = validate(badCard, modelBytes)
        assertTrue(found.any { "trainingCornerGroups must be empty" in it }, found.toString())
    }

    @Test
    fun `mismatched sha256 fails loudly`() {
        val badCard = JsonObject(card + ("sha256" to JsonPrimitive("0".repeat(64))))
        val found = validate(badCard, modelBytes)
        assertTrue(found.any { "sha256 mismatch" in it }, found.toString())
    }

    private companion object {
        const val EXCLUDED_LIVE_GROUP = "live/sm-s721u1_stkb_studio-a"
    }
}
