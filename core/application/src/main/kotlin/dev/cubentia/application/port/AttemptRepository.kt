package dev.cubentia.application.port

import dev.cubentia.application.session.AttemptRecord

interface AttemptRepository {
    suspend fun all(): AttemptLoad
}

sealed interface AttemptLoad {
    data class Found(
        val records: List<AttemptRecord>,
    ) : AttemptLoad

    data object Failed : AttemptLoad
}
