package dev.cubentia.app.camera

import android.media.MediaActionSound

/**
 * The capture shutter sound with a lifecycle-safe load, play and release (GH #158). [load] reads the sound from disk,
 * so call it off the main thread; it runs outside the lock, so a main-thread [play] or [release] never waits on the
 * disk. [play] does nothing until the load finished (so the platform never loads the sound itself on main) and nothing
 * after [release]. A [release] while a load is in flight leaves the actual release to the loader, which releases the
 * player as soon as its load returns. The flags are read and set under one lock.
 */
internal class ShutterSound(
    private val player: Player = MediaActionPlayer(),
) {
    /** The seam over [MediaActionSound], so the lifecycle rules are testable without the platform sound. */
    interface Player {
        fun load()

        fun play()

        fun release()
    }

    private val lock = Any()
    private var loading = false
    private var loaded = false
    private var released = false

    /** Loads the sound (disk I/O: call off main); skipped when already loading, loaded or released. */
    fun load() {
        synchronized(lock) {
            if (released || loading || loaded) return
            loading = true
        }
        try {
            player.load()
        } finally {
            synchronized(lock) {
                loading = false
                if (released) player.release() else loaded = true
            }
        }
    }

    /** Plays the shutter click once loaded; a no-op before the load finished or after [release]. */
    fun play() {
        synchronized(lock) {
            if (!loaded || released) return
            player.play()
        }
    }

    /** Releases the player once; with a load in flight the loader releases it when that load returns. */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            if (!loading) player.release()
        }
    }
}

private class MediaActionPlayer : ShutterSound.Player {
    private val sound = MediaActionSound()

    override fun load() = sound.load(MediaActionSound.SHUTTER_CLICK)

    override fun play() = sound.play(MediaActionSound.SHUTTER_CLICK)

    override fun release() = sound.release()
}
