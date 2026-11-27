package dev.cubentia.app.camera.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.QuickScanSession
import dev.cubentia.app.camera.QuickScanSessionFactory
import dev.cubentia.app.camera.QuickScanSessionHost
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Story 10.34 (AD-9 Frames, AD-15): binds the Quick Scan session recording and its writer. This module lives only in the
 * debug camera source set, so no other variant composes it (`check<Variant>FixtureWriter` fails one that would).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuickScanSessionModule {
    @Binds
    internal abstract fun writerFactory(impl: DebugContinuousSessionWriterFactory): ContinuousSessionWriterFactory

    @Binds
    internal abstract fun sessionFactory(impl: DebugQuickScanSessionFactory): QuickScanSessionFactory
}

/** One controller per Quick Scan screen, with its own io thread and its adb receiver registered while the screen lives. */
@Singleton
internal class DebugQuickScanSessionFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val writers: ContinuousSessionWriterFactory,
    ) : QuickScanSessionFactory {
        override fun create(host: QuickScanSessionHost): QuickScanSession {
            val io = Executors.newSingleThreadExecutor { Thread(it, "cubentia-session-io") }
            var receiver: BroadcastReceiver? = null
            val controller =
                QuickScanSessionController(
                    host = host,
                    writers = writers,
                    deviceModel = Build.MODEL ?: "unknown",
                    deviceManufacturer = Build.MANUFACTURER ?: "unknown",
                    io = io,
                    main = ContextCompat.getMainExecutor(context),
                    clockMs = SystemClock::elapsedRealtime,
                    log = { line -> runCatching { Log.i(QuickScanSessionController.TAG, line) } },
                    onRelease = {
                        receiver?.let { runCatching { context.unregisterReceiver(it) } }
                        // Queued work (a pending discard) still runs; nothing new is accepted.
                        io.shutdown()
                    },
                )
            receiver = QuickScanSessionReceiver.register(context, controller::command)
            return controller
        }
    }

/**
 * The adb trigger (answers 12, 16): `adb shell am broadcast -a dev.cubentia.debug.RECORDER_SESSION --es command
 * start|stop|save|discard|baseline [--es cube …] [--es lighting …] [--es partition TUNING|HELDOUT|UNASSIGNED] [--es label …]
 * -p com.exxerpro.cubentia`. Registered only from the debug binding while a Quick Scan screen lives, and only senders that
 * hold `android.permission.DUMP` reach it: `adb shell` holds it, an installed third-party app cannot.
 */
internal class QuickScanSessionReceiver(
    private val onCommand: (QuickScanSessionCommand) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            runCatching { Log.w(QuickScanSessionController.TAG, "RECORDER_SESSION without a command extra ignored") }
            return
        }
        onCommand(
            QuickScanSessionCommand(
                command = command.trim().lowercase(),
                cube = intent.getStringExtra(EXTRA_CUBE),
                lighting = intent.getStringExtra(EXTRA_LIGHTING),
                partition = intent.getStringExtra(EXTRA_PARTITION),
                label = intent.getStringExtra(EXTRA_LABEL),
            ),
        )
    }

    companion object {
        const val ACTION = "dev.cubentia.debug.RECORDER_SESSION"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CUBE = "cube"
        const val EXTRA_LIGHTING = "lighting"
        const val EXTRA_PARTITION = "partition"
        const val EXTRA_LABEL = "label"

        /** The permission a sender must hold: the shell has it, a third-party app cannot be granted it. */
        const val SENDER_PERMISSION = "android.permission.DUMP"

        /** Registers a receiver for [onCommand] on the main thread; unregister it with [Context.unregisterReceiver]. */
        fun register(
            context: Context,
            onCommand: (QuickScanSessionCommand) -> Unit,
        ): QuickScanSessionReceiver =
            QuickScanSessionReceiver(onCommand).also { receiver ->
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION),
                    SENDER_PERMISSION,
                    null,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
    }
}
