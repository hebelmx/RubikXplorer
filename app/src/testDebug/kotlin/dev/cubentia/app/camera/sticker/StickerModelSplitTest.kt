package dev.cubentia.app.camera.sticker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * Story 10.23 (AC2), in CI: the sticker model's committed split (`tools/sticker-model/split.json`) holds whole capture
 * groups apart. No group and no image (by its SHA-256, recomputed from the committed file) is on two sides, synthetic faces
 * are training-only, every group `holdout.json` holds out is on the held-out side, and the held-out groups cover both
 * devices, a stickered and a stickerless cube and two backlit groups. The crafted cases prove the check fails.
 */
class StickerModelSplitTest {
    private val repo = File("..")
    private val holdout = parse(File(repo, "tools/sticker-model/holdout.json"))
    private val split = parse(File(repo, "tools/sticker-model/split.json"))

    private fun parse(file: File) = Json.parseToJsonElement(file.readText()).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Every failure, naming the group or the image hash; empty when the split is sound. */
    private fun failures(
        split: JsonObject,
        rehash: Boolean = true,
    ): List<String> {
        val failures = mutableListOf<String>()
        val sideOf = mutableMapOf<String, String>()
        split.getValue("groups").jsonArray.map { it.jsonObject }.forEach { group ->
            val name = group.text("group")
            val side = group.text("side")
            if (side !in setOf("train", "tune", "heldout")) failures += "group $name has unknown side $side"
            sideOf[name]?.let { if (it != side) failures += "group $name is on two sides: $it and $side" }
            sideOf.putIfAbsent(name, side)
            if (group.text("kind") == "synthetic" && side != "train") failures += "synthetic group $name is on $side"
        }
        holdout.getValue("heldout").jsonArray.map { it.jsonPrimitive.content }.forEach { name ->
            if (sideOf[name] != "heldout") failures += "holdout.json holds out $name, but the split has it on ${sideOf[name]}"
        }
        holdout.getValue("tune").jsonArray.map { it.jsonPrimitive.content }.forEach { name ->
            if (sideOf[name] != "tune") failures += "holdout.json tunes on $name, but the split has it on ${sideOf[name]}"
        }
        val hashSides = mutableMapOf<String, MutableSet<String>>()
        split.getValue("items").jsonArray.map { it.jsonObject }.forEach { item ->
            val group = item.text("group")
            val side = item.text("side")
            val groupSide = sideOf[group]
            if (groupSide == null) {
                failures += "item ${item.text("item")} belongs to unlisted group $group"
            } else if (groupSide != side) {
                failures += "group $group is on two sides: item ${item.text("item")} is on $side, the group on $groupSide"
            }
            if (group.startsWith("synthetic/") && side != "train") failures += "synthetic item ${item.text("item")} is on $side"
            var digest = item.text("sha256")
            if (rehash) {
                val source = File(repo, item.text("source"))
                if (!source.isFile) {
                    failures += "item ${item.text("item")}: ${item.text("source")} is missing"
                } else {
                    val actual = sha256(source)
                    if (actual != digest) failures += "item ${item.text("item")}: image hash $actual differs from the recorded $digest"
                    digest = actual
                }
            }
            hashSides.getOrPut(digest) { mutableSetOf() } += side
        }
        hashSides.filterValues { it.size > 1 }.forEach { (digest, sides) -> failures += "image hash $digest is on sides ${sides.sorted()}" }
        val held =
            split.getValue("groups").jsonArray.map { it.jsonObject }.filter {
                it.text("side") == "heldout" &&
                    it.text("kind") == "real"
            }
        if (held.map { it.text("device") }.toSet().size < 2) failures += "held-out groups cover one device only"
        if (held.map { it.getValue("stickered").jsonPrimitive.boolean }.toSet() != setOf(true, false)) {
            failures += "held-out groups do not cover both a stickered and a stickerless cube"
        }
        if (held.count { "backlit" in it.text("light") } < 2) failures += "fewer than two held-out groups are backlit"
        return failures
    }

    /** [split] with [change] applied to the first item (or group) matching [where]. */
    private fun crafted(
        collection: String,
        where: (JsonObject) -> Boolean,
        change: (JsonObject) -> JsonObject,
    ): JsonObject {
        var done = false
        val entries =
            split.getValue(collection).jsonArray.map { element ->
                val entry = element.jsonObject
                if (!done && where(entry)) {
                    done = true
                    change(entry)
                } else {
                    entry
                }
            }
        check(done) { "nothing to craft in $collection" }
        return JsonObject(split + (collection to JsonArray(entries)))
    }

    private fun JsonObject.with(
        key: String,
        value: String,
    ) = JsonObject(this + (key to JsonPrimitive(value)))

    @Test
    fun `the committed split passes`() {
        assertEquals(emptyList<String>(), failures(split))
        assertTrue(split.getValue("items").jsonArray.isNotEmpty())
    }

    @Test
    fun `a held-out item moved to train puts its group on two sides`() {
        val bad = crafted("items", { it.text("side") == "heldout" }) { it.with("side", "train") }
        val found = failures(bad)
        assertTrue(found.any { it.startsWith("group ") && "is on two sides" in it }, found.toString())
    }

    @Test
    fun `a training item carrying a held-out image hash is caught by the hash`() {
        val heldHash =
            split
                .getValue("items")
                .jsonArray
                .map { it.jsonObject }
                .first { it.text("side") == "heldout" }
                .text("sha256")
        val bad = crafted("items", { it.text("side") == "train" }) { it.with("sha256", heldHash) }
        val found = failures(bad, rehash = false)
        assertTrue(found.any { it.startsWith("image hash $heldHash is on sides") }, found.toString())
    }

    @Test
    fun `a held-out group moved to train fails, naming it`() {
        val name =
            holdout
                .getValue("heldout")
                .jsonArray
                .first()
                .jsonPrimitive.content
        val bad = crafted("groups", { it.text("group") == name }) { it.with("side", "train") }
        val found = failures(bad)
        assertTrue(found.any { "holdout.json holds out $name" in it }, found.toString())
    }

    @Test
    fun `a synthetic group outside train fails`() {
        val bad = crafted("groups", { it.text("kind") == "synthetic" }) { it.with("side", "tune") }
        val found = failures(bad)
        assertTrue(found.any { it.startsWith("synthetic group ") }, found.toString())
    }

    @Test
    fun `a changed image file fails its recorded hash`() {
        val bad = crafted("items", { true }) { it.with("sha256", "0".repeat(64)) }
        val found = failures(bad)
        assertTrue(found.any { "differs from the recorded" in it }, found.toString())
    }
}
