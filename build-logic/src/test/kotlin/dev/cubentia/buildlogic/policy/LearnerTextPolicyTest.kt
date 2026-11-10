package dev.cubentia.buildlogic.policy

import dev.cubentia.buildlogic.policy.LearnerTextPolicy.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LearnerTextPolicyTest {
    @Test fun `resource names must be in the baseline`() {
        val xml =
            """<resources><string name="allowed">Text</string><plurals name="new_name" />""" +
                """<string-array name="new_array" /></resources>"""
        val found = LearnerTextPolicy.checkStringResources("strings.xml", xml, setOf("allowed"))
        assertEquals(listOf("new_name", "new_array"), found.map { it.literal })
        assertEquals(listOf(Rule.STRING_RESOURCE, Rule.STRING_RESOURCE), found.map { it.rule })
        assertEquals(
            emptyList<LearnerTextPolicy.Violation>(),
            LearnerTextPolicy.checkStringResources("strings.xml", xml, setOf("allowed", "new_name", "new_array")),
        )
    }

    @Test fun `an item resource of type string must be in the baseline, in either attribute order`() {
        val xml =
            """<resources>
              |<item type="string" name="new_learner_text">New Learner text</item>
              |<item name="other_learner_text" type="string">Other Learner text</item>
              |<item type="dimen" name="not_text">4dp</item>
              |<string-array name="allowed"><item>Not a resource</item></string-array>
              |</resources>
            """.trimMargin()
        val found = LearnerTextPolicy.checkStringResources("strings.xml", xml, setOf("allowed"))
        assertEquals(
            listOf(
                LearnerTextPolicy.Violation("strings.xml", 2, "new_learner_text", Rule.STRING_RESOURCE),
                LearnerTextPolicy.Violation("strings.xml", 3, "other_learner_text", Rule.STRING_RESOURCE),
            ),
            found,
        )
        assertEquals(
            emptyList<LearnerTextPolicy.Violation>(),
            LearnerTextPolicy.checkStringResources(
                "strings.xml",
                xml,
                setOf("allowed", "new_learner_text", "other_learner_text"),
            ),
        )
    }

    private fun violations(source: String) = LearnerTextPolicy.check("F.kt", source.trimIndent())

    private fun rules(source: String) = violations(source).map { it.line to it.rule }

    @Test
    fun `a literal passed to Compose Text is reported with its line`() {
        val found = violations("package a\n\n@Composable fun Bar() {\n    Text(\"Undo\")\n}")
        assertEquals(listOf(LearnerTextPolicy.Violation("F.kt", 4, "Undo", Rule.UI_TEXT)), found)
        assertEquals("F.kt:4: ${Rule.UI_TEXT.description}: \"Undo\"", found.single().toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["text", "contentDescription", "label", "placeholder", "title", "stateDescription", "onClickLabel"])
    fun `a literal for a Learner-facing named argument is reported`(name: String) {
        assertEquals(listOf(1 to Rule.UI_TEXT), rules("Icon(painter, $name = \"Undo\")"))
    }

    @Test
    fun `a multi-word literal in core is reported`() {
        assertEquals(listOf(1 to Rule.PROSE), rules("val s = \"Turn the top\""))
        assertEquals(listOf(2 to Rule.PROSE), rules("val a = 1\nval s = \"\"\"Turn\n the top\"\"\""))
        assertEquals(listOf(1 to Rule.PROSE), rules("val s = \"Turn \${face.name} now\""))
    }

    @Test
    fun `content keys, single words, symbols and comparisons pass`() {
        val source =
            """
            val r = Text("msg.validation.parity", mapOf("piece" to id))
            val tag = Modifier.testTag("toolbar:undo")
            val sep = list.joinToString(" ")
            val re = Regex("stage-\\d{2}-[a-z0-9]+(-[a-z0-9]+)*")
            val fmt = "%02x".format(b)
            val path = "${'$'}base/${'$'}path"
            val field = "steps.${'$'}index.moves"
            val pair = "${'$'}a ${'$'}{b.c}"
            val same = title == "x"
            val ch = '"'
            val esc = "a\"b"
            val ok = Text(stringResource(R.string.toolbar_undo))
            val keyed = Text(key = "msg.validation.parity")
            """
        assertEquals(emptyList<Pair<Int, Rule>>(), rules(source))
    }

    @Test
    fun `comments are ignored`() {
        val source =
            """
            // Text("Undo") and "two words" in a line comment
            /* "two words" /* nested "more words" */ still comment */
            /** KDoc: e.g. "R prime: right face" */
            val x = 1
            """
        assertEquals(emptyList<Pair<Int, Rule>>(), rules(source))
    }

    @Test
    fun `programmer-error contexts may hold prose`() {
        val source =
            """
            fun f(x: Int) {
                require(x > 0) { "x must be positive, was ${'$'}x" }
                requireNotNull(y) { "y is missing here" }
                check(x < 9) { "too big ${'$'}{x.plus(1)}" }
                checkNotNull(pieceToHome(a, b)) { "no piece holds ${'$'}x" }
                val v = map[x] ?: error("Unknown feature flag '${'$'}{parts[0]}' in '${'$'}e'")
                throw IllegalStateException("bad state here")
                throw Oops("not an exception name but thrown")
                return GradleException("module check failed:\n" + list.joinToString("\n") { "  - ${'$'}it" })
            }
            class H {
                override fun toString(): String = "MoveHistory(state=${'$'}s, done=${'$'}d)"
            }
            class B {
                override fun toString(): String {
                    val x = 1
                    return "Block body ${'$'}x"
                }
            }
            """
        assertEquals(emptyList<Pair<Int, Rule>>(), rules(source))
    }

    @Test
    fun `prose after a toString or outside an error call is still reported`() {
        val source =
            """
            class H {
                override fun toString(): String = "fine as debug text"
                val later = "but not this one"
                fun other() {
                    require(true) { "fine here" }
                    println("Turn the top")
                }
            }
            """
        assertEquals(listOf(3 to Rule.PROSE, 6 to Rule.PROSE), rules(source))
    }

    @Test
    fun `a literal nested in a template is checked on its own`() {
        assertEquals(listOf(1 to Rule.PROSE), rules("val s = \"x\${if (a) \"two words\" else \"\"}\""))
    }

    @Test
    fun `a content key is exempt only as the first argument of Text`() {
        assertEquals(listOf(1 to Rule.UI_TEXT), rules("Icon(painter, contentDescription = \"toolbar.undo\")"))
    }

    @Test
    fun `BasicText and AnnotatedString literals are reported, content keys included`() {
        assertEquals(
            listOf(1 to Rule.UI_TEXT, 2 to Rule.UI_TEXT, 3 to Rule.UI_TEXT),
            rules("BasicText(\"Undo\")\nval a = AnnotatedString(\"Undo\")\nBasicText(\"msg.validation.parity\")"),
        )
    }

    @Test
    fun `only an Exception constructor is a programmer-error context`() {
        assertEquals(listOf(1 to Rule.PROSE), rules("handleException(\"two words\")"))
    }

    @Test
    fun `Unavailable result diagnostics may hold prose`() {
        assertEquals(emptyList<Pair<Int, Rule>>(), rules("ClassificationResult.Unavailable(\"two word diagnostic\")"))
        assertEquals(listOf(1 to Rule.PROSE), rules("val reason = \"two word diagnostic\""))
    }

    @Test
    fun `a template counts as a word`() {
        val source =
            """
            val a = "${'$'}count moves"
            val b = "${'$'}{n} left"
            val c = "${'$'}a ${'$'}b"
            """
        assertEquals(listOf(1 to Rule.PROSE, 2 to Rule.PROSE), rules(source))
    }

    @Test
    fun `declaration keywords need a word boundary and include constructor and typealias`() {
        val source =
            """
            class H {
                override fun toString(): String = objective.let { "debug text ${'$'}it" }
                constructor(a: Int) : this() {
                    println("Turn the top")
                }
            }
            """
        assertEquals(listOf(4 to Rule.PROSE), rules(source))
        assertEquals(
            listOf(3 to Rule.PROSE),
            rules("class T {\n    override fun toString() = \"debug text\"\n    typealias X = \"not real kotlin\"\n}"),
        )
    }

    @Test
    fun `a char literal inside a template does not end the scan`() {
        assertEquals(listOf(2 to Rule.PROSE), rules("val s = \"${'$'}{s.trim('{')}\"\nval t = \"two words\""))
    }

    @Test
    fun `Room Query annotations hold SQL while ordinary calls and literals remain checked`() {
        val source = """
            import androidx.room3.Query
            @Query("SELECT * FROM session_snapshot")
            suspend fun latest(): Row?
            @Query(value = "SELECT COUNT(*) FROM session_snapshot")
            suspend fun count(): Long
            @androidx.room3.Query("SELECT * FROM session_snapshot WHERE kind = :kind")
            suspend fun find(kind: String): Row?
            val text = "Turn the top"
            Query("Turn the top")
            Text("Turn the top")
        """
        assertEquals(listOf(8 to Rule.UI_TEXT, 9 to Rule.PROSE, 10 to Rule.UI_TEXT), rules(source))
        assertEquals(listOf(1 to Rule.PROSE), rules("@Query(\"SELECT * FROM session_snapshot\")"))
        assertEquals(emptyList<Pair<Int, Rule>>(), rules("@androidx.room3.Query(\"SELECT * FROM session_snapshot\")"))
    }
}
