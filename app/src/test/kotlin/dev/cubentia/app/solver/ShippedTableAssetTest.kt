package dev.cubentia.app.solver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.cubentia.app.di.SolverModule
import dev.cubentia.solver.PruningTableLoadResult
import dev.cubentia.solver.PruningTableSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 3.8: the merged APK assets carry a valid table file that the production provider serves as BUNDLED. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShippedTableAssetTest {
    @Test
    fun `production provider loads the shipped asset without generating or writing`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val loaded = SolverModule.provider(context).load()
            assertEquals(PruningTableSource.BUNDLED, (loaded as PruningTableLoadResult.Ready).source)
            assertEquals(0L, loaded.generationNanos)
            assertFalse(SolverModule.fallbackFile(context.filesDir).exists())
        }
}
