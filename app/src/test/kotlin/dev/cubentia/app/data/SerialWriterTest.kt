package dev.cubentia.app.data

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SerialWriterTest {
    @Test fun hooksRunBeforeEveryOperationAndSurviveFailures() =
        runBlocking {
            val writer = SerialWriter()
            val order = mutableListOf<String>()
            writer.beforeEach {
                order.add("hook")
                error("retry failed")
            }
            writer.beforeEach { throw kotlinx.coroutines.CancellationException("operation cancelled") }
            writer.enqueue { order.add("write") }
            writer.read { order.add("read") }
            assertEquals(listOf("hook", "write", "hook", "read"), order)
            writer.close()
        }

    @Test fun fifoAndOffMainIncludingSuspendedWork() =
        runBlocking {
            val writer = SerialWriter()
            val results = mutableListOf<Int>()
            val offMain = mutableListOf<Boolean>()
            repeat(30) { i ->
                writer.enqueue {
                    offMain += (Thread.currentThread() != Looper.getMainLooper().thread)
                    delay(1)
                    results += i
                }
            }
            writer.flush()
            assertEquals((0 until 30).toList(), results)
            assertTrue(offMain.all { it })
            writer.enqueue { error("adapter failure") }
            assertEquals(42, writer.read { 42 })
            writer.close()
        }
}
