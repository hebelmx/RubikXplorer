package dev.cubentia.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** GH #158: the shutter sound never plays or loads after release, and a release during a load still releases. */
class ShutterSoundTest {
    private class Recorder(
        private val onLoad: () -> Unit = {},
    ) : ShutterSound.Player {
        val calls = mutableListOf<String>()

        override fun load() {
            onLoad()
            synchronized(calls) { calls += "load" }
        }

        override fun play() {
            synchronized(calls) { calls += "play" }
        }

        override fun release() {
            synchronized(calls) { calls += "release" }
        }
    }

    @Test
    fun `loads, plays and releases once`() {
        val player = Recorder()
        val sound = ShutterSound(player)
        sound.load()
        sound.play()
        sound.release()
        sound.release()
        assertEquals(listOf("load", "play", "release"), player.calls)
    }

    @Test
    fun `a play before the load finished plays nothing`() {
        val player = Recorder()
        val sound = ShutterSound(player)
        sound.play()
        sound.load()
        sound.play()
        assertEquals(listOf("load", "play"), player.calls)
    }

    @Test
    fun `a failed load leaves the sound unloaded, so play does nothing and release still releases`() {
        val player = Recorder { throw IllegalStateException("no sound") }
        val sound = ShutterSound(player)
        assertTrue(runCatching { sound.load() }.isFailure)
        sound.play()
        sound.release()
        assertEquals(listOf("release"), player.calls)
    }

    @Test
    fun `loadOrSkip swallows a throwing load, so play stays a no-op`() {
        val player = Recorder { throw IllegalStateException("no sound") }
        val sound = ShutterSound(player)
        sound.loadOrSkip()
        sound.play()
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun `a capture effect posted after release plays nothing, and a late load is skipped`() {
        val player = Recorder()
        val sound = ShutterSound(player)
        sound.release()
        sound.play()
        sound.load()
        assertEquals(listOf("release"), player.calls)
    }

    @Test
    fun `a release while the load is in flight returns at once and the loader releases after the load`() {
        val loading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val player =
            Recorder {
                loading.countDown()
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
            }
        val sound = ShutterSound(player)
        val loader = thread { sound.load() }
        assertTrue("load started", loading.await(5, TimeUnit.SECONDS))
        // Main is never blocked by the disk read: release returns while the load is still blocked.
        val released = CountDownLatch(1)
        val releaser =
            thread {
                sound.release()
                released.countDown()
            }
        assertTrue("release returned during the load", released.await(5, TimeUnit.SECONDS))
        assertTrue(loader.isAlive)
        assertEquals(emptyList<String>(), synchronized(player.calls) { player.calls.toList() })
        sound.play()
        proceed.countDown()
        loader.join(5_000)
        releaser.join(5_000)
        assertFalse("loader finished", loader.isAlive)
        assertFalse("releaser finished", releaser.isAlive)
        sound.play()
        assertEquals(listOf("load", "release"), player.calls)
    }
}
