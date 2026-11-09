package dev.cubentia.application.port

import kotlin.time.Instant

/**
 * Port to the wall clock (AD-11). Only the session host reads it, to stamp each `SessionEvent` with
 * `at` at dispatch; the reducer never does, so a transition depends on its inputs alone.
 */
fun interface Clock {
    fun now(): Instant
}
