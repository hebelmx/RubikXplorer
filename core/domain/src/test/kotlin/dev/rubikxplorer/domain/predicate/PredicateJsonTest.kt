package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.content.ContentLoader
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 1–2: each registry variant decodes from its flat content JSON and round-trips. */
class PredicateJsonTest {
    private val json = ContentLoader.json

    private val cases: Map<String, Predicate> =
        mapOf(
            """{"predicate":"white-cross-solved"}""" to WhiteCrossSolved,
            """{"predicate":"white-corners-solved"}""" to WhiteCornersSolved,
            """{"predicate":"state-reached","moves":"R U"}""" to StateReached("R U"),
            """{"predicate":"white-piece-solved","piece":"white-green-red"}""" to WhitePieceSolved("white-green-red"),
            """{"predicate":"nothing"}""" to AlwaysTrue,
            """{"predicate":"allOf","predicates":[{"predicate":"white-cross-solved"},{"predicate":"white-corners-solved"}]}""" to
                AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)),
            """{"predicate":"anyOf","predicates":[{"predicate":"state-reached","moves":"R"},""" +
                """{"predicate":"allOf","predicates":[{"predicate":"white-cross-solved"}]}]}""" to
                AnyOf(listOf(StateReached("R"), AllOf(listOf(WhiteCrossSolved)))),
        )

    @Test
    fun `each variant decodes from flat JSON and encodes back to the same text`() {
        cases.forEach { (text, expected) ->
            val decoded = json.decodeFromString<Predicate>(text)
            assertEquals(expected, decoded, text)
            assertEquals(text, json.encodeToString<Predicate>(decoded), text)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `an unknown nested discriminator, bad moves or an empty combinator fail to decode`() {
        val nested =
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<Predicate>("""{"predicate":"allOf","predicates":[{"predicate":"nope"}]}""")
            }
        assertTrue("nope" in nested.message.orEmpty(), nested.message)
        val badMoves =
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString<Predicate>("""{"predicate":"state-reached","moves":"Q"}""")
            }
        assertTrue("not valid Notation" in badMoves.message.orEmpty(), badMoves.message)
        val empty =
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString<Predicate>("""{"predicate":"anyOf","predicates":[]}""")
            }
        assertTrue("anyOf needs at least one predicate" in empty.message.orEmpty(), empty.message)
        val missing =
            assertThrows(MissingFieldException::class.java) {
                json.decodeFromString<Predicate>("""{"predicate":"state-reached"}""")
            }
        assertTrue("moves" in missing.missingFields, missing.message)
    }
}
