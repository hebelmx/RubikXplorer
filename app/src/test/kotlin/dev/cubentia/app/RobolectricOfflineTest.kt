package dev.cubentia.app

import android.app.Application
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Epic 5 retro item 23: Robolectric runs offline from the Gradle-resolved android-all jar, never from Maven. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RobolectricOfflineTest {
    /** android-all version prefix per SDK the suite runs (robolectric.properties: sdk=34). */
    private val androidAllPrefix = mapOf(34 to "14-")

    @Test
    fun runsOfflineFromTheGradleResolvedJar() {
        assertEquals("true", System.getProperty("robolectric.offline"))
        val dir = File(checkNotNull(System.getProperty("robolectric.dependency.dir")) { "robolectric.dependency.dir unset" })
        assertTrue("$dir missing", dir.isDirectory)
        val prefix = checkNotNull(androidAllPrefix[Build.VERSION.SDK_INT]) { "no android-all pin for SDK ${Build.VERSION.SDK_INT}" }
        val jars = dir.listFiles().orEmpty().map { it.name }
        assertTrue(
            "no android-all-instrumented-$prefix*.jar in $dir: $jars",
            jars.any { it.startsWith("android-all-instrumented-$prefix") && it.endsWith(".jar") },
        )
    }
}
