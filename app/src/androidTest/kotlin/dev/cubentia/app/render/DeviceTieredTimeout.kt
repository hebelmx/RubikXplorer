package dev.cubentia.app.render

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.TimeUnit

/**
 * Marks a test method whose timeout [DeviceTieredTimeoutRule] chooses from the device's [DeviceSpeedTier] (#314).
 * The test class must declare [DeviceTieredTimeoutRule] (ordered inside the compose rule), or no timeout applies.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DeviceTieredTimeout

/**
 * Applies the [DeviceSpeedTier] render sync timeout to methods annotated [DeviceTieredTimeout], read from the
 * instrumentation target's total RAM; unannotated methods pass through unchanged. Like `@Test(timeout)`, the
 * [Timeout] runs the test body on a separate thread, so order this rule inside the compose rule.
 */
class DeviceTieredTimeoutRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement {
        if (description.getAnnotation(DeviceTieredTimeout::class.java) == null) return base
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val tier = DeviceSpeedTier.fromTotalMemoryBytes(memory.totalMem)
        Log.i(TAG, "${description.methodName}: totalMem=${memory.totalMem} tier=$tier limit=${tier.renderSyncTimeoutMillis} ms")
        return Timeout
            .builder()
            .withTimeout(tier.renderSyncTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .apply(base, description)
    }

    private companion object {
        const val TAG = "DeviceTieredTimeout"
    }
}
