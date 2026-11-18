package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScanAssemblerCornerTest {
    private val top = List(9) { Color.entries[it % 6] }.toMutableList().apply { this[4] = Color.YELLOW }
    private val left = List(9) { Color.GREEN }
    private val right = List(9) { Color.ORANGE }
    private val first = CornerView(top, left, right)

    @Test
    fun mapsCornerToLastLayerOrderAndKeepsLowerRows() {
        val (view, lowerRows) = ScanAssembler.lastLayerView(first) as CornerLastLayer.View
        assertEquals(
            listOf(top[7], top[6]) + left.take(3) + listOf(top[5], top[8]) + right.take(3) +
                listOf(top[1], top[2], null, null, null, top[3], top[0], null, null, null),
            view.stickers,
        )
        assertEquals(listOf(Color.GREEN, Color.ORANGE, Color.BLUE, Color.RED), view.ring)
        assertEquals(List(5) { Color.GREEN to Color.GREEN } + List(5) { Color.ORANGE to Color.ORANGE }, lowerRows)
    }

    @Test
    fun oppositeCornerFillsBothMissingSidesAndDiscardsConflictingTopSticker() {
        val rotatedTop = top.reversed().toMutableList().apply { this[7] = Color.WHITE }
        val opposite = CornerView(rotatedTop, List(9) { Color.BLUE }, List(9) { Color.RED })
        val (view, lowerRows) = ScanAssembler.lastLayerView(first, opposite) as CornerLastLayer.View
        assertEquals(listOf(null, top[2], Color.BLUE, Color.BLUE, Color.BLUE), view.stickers.subList(10, 15))
        assertEquals(listOf(top[3], top[0], Color.RED, Color.RED, Color.RED), view.stickers.takeLast(5))
        assertEquals(20, lowerRows.size)
    }

    @Test
    fun rejectsNonAdjacentSidesAndNonOppositePair() {
        assertEquals(CornerLastLayer.NotOnRing, ScanAssembler.lastLayerView(first.copy(right = List(9) { Color.BLUE })))
        assertEquals(CornerLastLayer.NotOnRing, ScanAssembler.lastLayerView(first, first))
        assertEquals(CornerLastLayer.NotOnRing, ScanAssembler.lastLayerView(first.copy(left = List(9) { Color.WHITE })))
    }
}
