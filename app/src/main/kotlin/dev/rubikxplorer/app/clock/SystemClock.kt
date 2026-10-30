package dev.rubikxplorer.app.clock

import dev.rubikxplorer.application.port.Clock
import javax.inject.Inject
import kotlin.time.Instant

/** The [Clock] adapter over the system wall clock (AD-11). */
class SystemClock
    @Inject
    constructor() : Clock {
        override fun now(): Instant =
            kotlin.time.Clock.System
                .now()
    }
