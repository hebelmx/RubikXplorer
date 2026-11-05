package dev.rubikxplorer.application.port

import dev.rubikxplorer.application.session.AttemptRecord

interface AttemptRepository {
    suspend fun all(): AttemptLoad
}

sealed interface AttemptLoad {
    data class Found(
        val records: List<AttemptRecord>,
    ) : AttemptLoad

    data object Failed : AttemptLoad
}
