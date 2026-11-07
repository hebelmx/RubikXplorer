package dev.rubikxplorer.app.ui.freecube

import android.app.Application
import dev.rubikxplorer.application.freecube.FreeCubeEvent
import dev.rubikxplorer.application.freecube.reduceFreeCube
import dev.rubikxplorer.application.port.RandomSource
import dev.rubikxplorer.application.usecase.NewScramble
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.MoveIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FreeCubeViewModelTest {
    private val move = FaceTurn(Face.U, Turn.CLOCKWISE)

    @Test
    fun `all input methods dispatch reducer events including externally generated scramble`() {
        val scramble =
            NewScramble(
                object : RandomSource {
                    override fun nextInt(until: Int) = 0
                },
            )
        val vm = FreeCubeViewModel(scramble = scramble)
        val actions =
            listOf<Pair<FreeCubeEvent, () -> Unit>>(
                FreeCubeEvent.Undo to { vm.onUndo() },
                FreeCubeEvent.Redo to { vm.onRedo() },
                FreeCubeEvent.MoveCommitted(move) to { vm.onMoveIntent(MoveIntent(move)) },
                FreeCubeEvent.Undo to { vm.onUndo() },
                FreeCubeEvent.Redo to { vm.onRedo() },
                FreeCubeEvent.Reset to { vm.onReset() },
                FreeCubeEvent.Reset to { vm.onReset() },
                FreeCubeEvent.Scrambled(scramble()) to { vm.onScramble() },
            )
        actions.forEach { (event, action) ->
            val expected = reduceFreeCube(vm.state, event)
            val previousChange = vm.lastChange
            action()
            assertEquals(expected.state, vm.state)
            if (expected.presentation == null) {
                assertEquals(previousChange, vm.lastChange)
            } else {
                assertNotNull(vm.lastChange)
            }
        }
        assertEquals(NewScramble.LENGTH, vm.history.done.size)
    }

    @Test
    fun `equal gesture and key intents produce identical transitions`() {
        val gesture = FreeCubeViewModel()
        val key = FreeCubeViewModel()
        repeat(3) {
            gesture.onMoveIntent(MoveIntent(move))
            key.onMoveIntent(MoveIntent(move))
            assertEquals(gesture.state, key.state)
            assertEquals(gesture.lastChange, key.lastChange)
        }
        val fresh = FreeCubeViewModel()
        assertEquals(0L, fresh.state.revision)
        assertFalse(fresh.history.canUndo)
        assertNull(fresh.lastChange)
    }
}
