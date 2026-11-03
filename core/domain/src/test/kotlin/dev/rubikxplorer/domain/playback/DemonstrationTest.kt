package dev.rubikxplorer.domain.playback

import dev.rubikxplorer.domain.content.ContentProblem
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationError
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 2: a Lesson's Demonstration, its per-step focus, and the sticker → piece lookup. */
class DemonstrationTest {
    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun lesson(
        entry: String,
        vararg stepMoves: String,
        entryState: String = FaceletString.encode(CubeState.SOLVED.apply(moves(entry)).toFacelets()),
        completion: Predicate = WhiteCrossSolved,
    ): Lesson =
        Lesson(
            id = LessonId("lesson-01-01"),
            stageId = StageId("stage-01-basics"),
            prerequisites = emptyList(),
            objective = TextKey("lesson.lesson-01-01.objective"),
            entryState = entryState,
            demonstration = stepMoves.joinToString(" "),
            learnerAction = TextKey("lesson.lesson-01-01.learner-action"),
            completion = completion,
            preserve = WhiteCrossSolved,
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                stepMoves.mapIndexed { i, m ->
                    Step(
                        goal = TextKey("lesson.lesson-01-01.goal.${i + 1}"),
                        preserve = TextKey("lesson.lesson-01-01.keep.${i + 1}"),
                        watch = TextKey("lesson.lesson-01-01.watch.${i + 1}"),
                        notice = TextKey("lesson.lesson-01-01.notice.${i + 1}"),
                        moves = m,
                        done = completion,
                    )
                },
        )

    private fun ready(lesson: Lesson): Demonstration = (Demonstration.of(lesson) as DemonstrationResult.Ready).demonstration

    private fun piece(vararg colors: Color) = Piece(colors.toSet())

    private val whiteGreen = piece(Color.WHITE, Color.GREEN)
    private val otherCrossEdges = setOf(piece(Color.WHITE, Color.RED), piece(Color.WHITE, Color.BLUE), piece(Color.WHITE, Color.ORANGE))

    /** Every corner and edge of a solved cube, named by color. */
    private val allPieces: Set<Piece> =
        (0 until Facelets.SIZE).mapNotNull { Piece.at(CubeState.SOLVED.toFacelets(), it) }.toSet()

    @Test
    fun `the white cross lesson focuses Keep, the white-green target and the F layer`() {
        val demo = ready(lesson("F", "F'"))
        assertEquals(CubeState.SOLVED.apply(moves("F")), demo.start)
        assertEquals(moves("F'"), demo.moves)
        val focus = demo.steps.single().focus
        assertEquals(otherCrossEdges, focus.keep)
        assertEquals(setOf(whiteGreen), focus.target)
        assertEquals(allPieces.filter { Color.GREEN in it.colors }.toSet(), focus.involved)
        assertEquals(otherCrossEdges + focus.involved, focus.lit)
        // Neither on the F layer nor a cross edge: dimmed.
        val dimmed = allPieces - focus.lit
        assertTrue(piece(Color.YELLOW, Color.BLUE) in dimmed)
        assertTrue(piece(Color.YELLOW, Color.BLUE, Color.ORANGE) in dimmed)
        assertEquals(20 - 8 - 3, dimmed.size)
    }

    @Test
    fun `Keep comes from preserve and the target from completion`() {
        // preserve = white cross, completion = white corners: a swap would make Keep the corners.
        val focus = ready(lesson("F", "F'", completion = WhiteCornersSolved)).steps.single().focus
        assertEquals(otherCrossEdges, focus.keep)
        assertEquals(setOf(piece(Color.WHITE, Color.GREEN, Color.RED), piece(Color.WHITE, Color.GREEN, Color.ORANGE)), focus.target)
    }

    @Test
    fun `a two-step lesson derives each step's focus from its own start and end`() {
        val demo = ready(lesson("F U", "U'", "F'"))
        assertEquals(moves("U' F'"), demo.moves)
        val (first, second) = demo.steps
        assertEquals(0, first.firstMove)
        assertEquals(1, second.firstMove)
        // Step 1 (U'): nothing kept yet; it brings the three other cross edges home; the U layer moves.
        assertEquals(emptySet<Piece>(), first.focus.keep)
        assertEquals(otherCrossEdges, first.focus.target)
        val uLayer = (0 until 9).mapNotNull { Piece.at(demo.start.toFacelets(), it) }.toSet()
        assertEquals(8, uLayer.size)
        assertEquals(uLayer, first.focus.involved)
        // Step 2 (F'): keeps those three and places the white-green edge.
        assertEquals(otherCrossEdges, second.focus.keep)
        assertEquals(setOf(whiteGreen), second.focus.target)
        assertEquals(allPieces.filter { Color.GREEN in it.colors }.toSet(), second.focus.involved)

        assertEquals(0, demo.stepIndexAt(0))
        assertEquals(1, demo.stepIndexAt(1))
        assertEquals(1, demo.stepIndexAt(9))
        assertEquals(0, demo.stepIndexAt(-3))
    }

    @Test
    fun `involved counts a piece that moves away and comes back within the step`() {
        val demo = ready(lesson("F", "R R' F'"))
        val involved =
            demo.steps
                .single()
                .focus.involved
        assertTrue(piece(Color.RED, Color.BLUE) in involved)
        assertTrue(piece(Color.RED, Color.YELLOW, Color.BLUE) in involved)
    }

    @Test
    fun `steps without Moves hold no Move index`() {
        val demo = ready(lesson("F", "", "F'", ""))
        assertEquals(1, demo.stepIndexAt(0))
        assertEquals(StepFocus(otherCrossEdges, emptySet(), emptySet()), demo.steps[0].focus)
        assertEquals(0, ready(lesson("", "", "")).stepIndexAt(0))
        assertEquals(0, ready(lesson("")).stepIndexAt(0))
    }

    @Test
    fun `unparsable step Moves are InvalidNotation`() {
        assertEquals(
            DemonstrationResult.InvalidNotation(1, NotationError.BadToken("Q", 0)),
            Demonstration.of(lesson("F", "F'", "Q")),
        )
    }

    @Test
    fun `a malformed or impossible entry state is InvalidEntryState`() {
        val malformed = Demonstration.of(lesson("", "F'", entryState = "WWW"))
        assertInstanceOf(
            ContentProblem.MalformedEntryState::class.java,
            (malformed as DemonstrationResult.InvalidEntryState).problem,
        )
        val solved = FaceletString.encode(CubeState.SOLVED.toFacelets()).toCharArray()
        solved[8] = solved[9].also { solved[9] = solved[8] }
        val impossible = Demonstration.of(lesson("", "F'", entryState = String(solved)))
        assertInstanceOf(
            ContentProblem.ImpossibleEntryState::class.java,
            (impossible as DemonstrationResult.InvalidEntryState).problem,
        )
    }

    @Test
    fun `every sticker maps to its piece, centers to none`() {
        listOf(CubeState.SOLVED, CubeState.SOLVED.apply(moves("D2 B' R2 U F' L D' B2 R' U2 F L2 x y'"))).forEach { state ->
            val facelets = state.toFacelets()
            val found = (0 until Facelets.SIZE).associateWith { Piece.at(facelets, it) }
            Face.entries.forEach { assertNull(found[it.ordinal * 9 + 4]) }
            val pieces = found.values.filterNotNull()
            assertEquals(48, pieces.size)
            assertEquals(allPieces, pieces.toSet())
            pieces.groupingBy { it }.eachCount().forEach { (p, n) -> assertEquals(p.colors.size, n) }
            found.forEach { (i, p) -> if (p != null) assertTrue(facelets.colors[i] in p.colors) }
        }
        // On a solved cube each piece holds the home colors of the faces its stickers lie on.
        val solved = CubeState.SOLVED.toFacelets()
        assertEquals(piece(Color.WHITE, Color.RED, Color.GREEN), Piece.at(solved, 8))
        assertEquals(piece(Color.WHITE, Color.GREEN), Piece.at(solved, 19))
    }
}
