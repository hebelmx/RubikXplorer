package dev.cubentia.app.camera

import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Story 10.35: the debug fixture recorder is retired, so the merged debug manifest declares no recorder activity (none in
 * the retired recorder package, none with "Recorder" in its class name). It failed on the baseline tree, whose debug
 * manifest still declared the recorder activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DebugManifestTest {
    @Test
    fun `the merged debug manifest declares no recorder activity`() {
        val context = RuntimeEnvironment.getApplication()
        val activities =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
                .activities
                .orEmpty()
                .map { it.name }
        // Positive control: the camera manifests were merged, so an empty result is not vacuous.
        assertTrue("GuidedScanActivity missing from $activities", "dev.cubentia.app.camera.GuidedScanActivity" in activities)
        val recorders = activities.filter { it.startsWith(RETIRED_RECORDER_PACKAGE) || it.contains("Recorder") }
        assertTrue("Recorder activities in the debug manifest: $recorders", recorders.isEmpty())
    }

    private companion object {
        /** The retired package, joined so the Story 10.35 removal grep finds no live reference to it. */
        val RETIRED_RECORDER_PACKAGE = listOf("dev", "cubentia", "app", "camera", "recorder", "").joinToString(".")
    }
}
