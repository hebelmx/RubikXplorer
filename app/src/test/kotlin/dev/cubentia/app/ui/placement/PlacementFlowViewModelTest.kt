package dev.cubentia.app.ui.placement

import androidx.lifecycle.viewModelScope
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.PlacementOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlacementFlowViewModelTest {
    private val vm by lazy { PlacementFlowViewModel() }
    private val stage = StageId("stage-03-first-layer-corners")

    @BeforeEach fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach fun reset() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun aSecondAcceptWhileSavingDoesNotSaveAgain() {
        val gate = CompletableDeferred<Boolean>()
        var saves = 0
        vm.accept(stage, faster = false) {
            saves++
            gate.await()
        }
        assertEquals(PlacementAcceptance.Saving, vm.acceptance.value)
        vm.accept(stage, faster = true) {
            saves++
            true
        }
        assertEquals(1, saves)
        gate.complete(true)
        assertEquals(PlacementAcceptance.Saved(stage, false), vm.acceptance.value)
    }

    @Test fun aThrowingSaveEndsFailed() {
        vm.accept(stage, faster = false) { error("disk") }
        assertEquals(PlacementAcceptance.Failed, vm.acceptance.value)
    }

    @Test fun aResetWhileSavingDropsTheResult() {
        val gate = CompletableDeferred<Boolean>()
        vm.accept(stage, faster = false) { gate.await() }
        vm.reset()
        gate.complete(true)
        assertEquals(PlacementAcceptance.Idle, vm.acceptance.value)
        assertNull(vm.consumeSaved())
    }

    @Test fun consumeSavedReturnsTheSavedAcceptanceOnce() {
        vm.accept(stage, faster = true) { true }
        assertEquals(PlacementAcceptance.Saved(stage, true), vm.consumeSaved())
        assertNull(vm.consumeSaved())
        assertEquals(PlacementAcceptance.Idle, vm.acceptance.value)
    }

    @Test fun theMatcherReachesPlacementFromTheEditor() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val matcher = (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as LastLayerMatcherResult.Built).matcher
        val id = CaseId("case-oll-01")
        val primary = bundle.algorithms.single { it.isPrimary && it.caseId == id }
        val state = (LastLayerMatcher.caseState(primary, Color.YELLOW) as CaseStateResult.Derived).state

        vm.editorAccepted(state, bundle.stages, matcher)
        assertEquals(PlacementPhase.Result(PlacementOutcome.AtLastLayer(bundle.stages[4], oriented = false, case = id)), vm.phase.value)
        vm.editorAccepted(state, bundle.stages)
        assertEquals(PlacementPhase.Result(PlacementOutcome.AtLastLayer(bundle.stages[4], oriented = false, case = null)), vm.phase.value)
    }

    @Test fun aPhaseChangeClearsAShownFailure() {
        vm.accept(stage, faster = false) { false }
        assertEquals(PlacementAcceptance.Failed, vm.acceptance.value)
        vm.scanDenied()
        assertEquals(PlacementPhase.Editing(Text("msg.solve.scan-camera-denied")), vm.phase.value)
        assertEquals(PlacementAcceptance.Idle, vm.acceptance.value)
    }
}
