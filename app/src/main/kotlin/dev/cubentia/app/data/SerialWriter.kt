package dev.cubentia.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** App-lifetime FIFO. A suspended I/O operation finishes before the next operation starts. */
class SerialWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (operation in queue) operation()
        }
    }

    fun enqueue(operation: suspend () -> Unit) {
        check(
            queue
                .trySend {
                    try {
                        operation()
                    } catch (cancelled: CancellationException) {
                        // Only the consumer's own cancellation stops the queue; an operation's does not.
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        // One failed adapter must not stop subsequent writes or flush barriers.
                    }
                }.isSuccess,
        )
    }

    suspend fun <T> read(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        enqueue {
            try {
                result.complete(operation())
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
        return result.await()
    }

    suspend fun flush() {
        read { Unit }
    }

    /** Drain before closing in tests; production keeps this writer for the app lifetime. */
    suspend fun close() {
        flush()
        queue.close()
    }
}
