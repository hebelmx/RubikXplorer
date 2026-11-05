package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.predicate.Fixtures.state
import dev.rubikxplorer.domain.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CubeSummaryTest {
    private val solved = CubeState.SOLVED
    private val cross = Text("msg.cube-summary.cross-solved")
    private val corners = Text("msg.cube-summary.corners-solved")

    @Test fun `one cross edge gives one of four`() {
        assertEquals(ObjectiveProgress(1, 4), WhiteCrossSolved.progress(state("F R B")))
        assertEquals(
            listOf(Text("msg.cube-summary.cross-progress", mapOf("done" to 1, "total" to 4))),
            CubeSummary.of(state("F R B"), WhiteCrossSolved),
        )
    }

    @Test fun `solved and partial composite summaries retain order and remove duplicates`() {
        val predicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, WhiteCrossSolved))
        assertEquals(listOf(cross, corners), CubeSummary.of(solved, predicate))
        val partial = state("R' D R L D L'")
        assertEquals(ObjectiveProgress(2, 4), WhiteCornersSolved.progress(partial))
        assertEquals(
            listOf(cross, Text("msg.cube-summary.corners-progress", mapOf("done" to 2, "total" to 4))),
            CubeSummary.of(partial, predicate),
        )
    }

    @Test fun `leaf progress comes from the predicate`() {
        listOf(WhiteCrossSolved to "cross", WhiteCornersSolved to "corners", StateReached("U") to "pattern").forEach { (predicate, name) ->
            val cube = state("R F")
            val progress = predicate.progress(cube)
            assertEquals(
                listOf(Text("msg.cube-summary.$name-progress", mapOf("done" to progress.done, "total" to progress.total))),
                CubeSummary.of(cube, predicate),
            )
        }
        assertEquals(listOf(Text("msg.cube-summary.pattern-reached")), CubeSummary.of(state("U"), StateReached("U")))
    }

    @Test fun `piece names use select identifiers`() {
        val piece = WhitePieceSolved("white-green")
        assertEquals(listOf(Text("msg.cube-summary.piece-placed", mapOf("piece" to "white_green"))), CubeSummary.of(solved, piece))
        assertEquals(listOf(Text("msg.cube-summary.piece-not-placed", mapOf("piece" to "white_green"))), CubeSummary.of(state("F"), piece))
    }

    @Test fun `any chooses satisfied first then exact best fraction with stable ties`() {
        assertEquals(listOf(cross), CubeSummary.of(solved, AnyOf(listOf(WhiteCrossSolved, WhiteCornersSolved))))
        val cube = state("F")
        val children = listOf(StateReached("R"), WhiteCornersSolved, WhiteCrossSolved)
        val best =
            children.maxWith { a, b ->
                val x = a.progress(cube)
                val y = b.progress(cube)
                (x.done.toLong() * y.total).compareTo(y.done.toLong() * x.total)
            }
        assertEquals(CubeSummary.of(cube, best), CubeSummary.of(cube, AnyOf(children)))
        assertEquals(emptyList<Text>(), CubeSummary.of(solved, AnyOf(listOf(AlwaysTrue, WhiteCrossSolved))))
    }

    @Test fun `any with neither child satisfied reads only the child with the better fraction`() {
        // F moves the white-green edge (0 of 1) and two white corners (2 of 4): the corners read better.
        val cube = state("F")
        val edge = WhitePieceSolved("white-green")
        assertEquals(ObjectiveProgress(0, 1), edge.progress(cube))
        assertEquals(ObjectiveProgress(2, 4), WhiteCornersSolved.progress(cube))
        assertEquals(
            listOf(Text("msg.cube-summary.corners-progress", mapOf("done" to 2, "total" to 4))),
            CubeSummary.of(cube, AnyOf(listOf(edge, WhiteCornersSolved))),
        )
    }

    @Test fun `pattern not yet reached on a solved cube counts matching pieces`() {
        assertEquals(
            listOf(
                Text(
                    "msg.cube-summary.pattern-progress",
                    mapOf("done" to StateReached("U").progress(solved).done, "total" to 20),
                ),
            ),
            CubeSummary.of(solved, StateReached("U")),
        )
    }

    @Test fun `nothing produces no parts`() {
        assertEquals(emptyList<Text>(), CubeSummary.of(solved, AlwaysTrue))
        assertEquals(emptyList<Text>(), CubeSummary.of(solved, AllOf(listOf(AlwaysTrue))))
    }
}
