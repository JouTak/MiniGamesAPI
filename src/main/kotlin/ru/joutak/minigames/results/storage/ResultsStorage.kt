package ru.joutak.minigames.results.storage

import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.TopPlayerIntMetric
import java.io.Closeable
import java.util.UUID

interface ResultsStorage : Closeable {
    fun ensureSchema()

    fun recordMatch(result: MatchResult): Boolean

    fun hasPlayerWon(
        eventId: String,
        stage: String,
        modeKey: String,
        playerUuid: UUID,
    ): Boolean

    fun getTopPlayerIntMetric(
        modeKey: String,
        metricKey: String,
        limit: Int,
        eventId: String? = null,
        stage: String? = null,
    ): List<TopPlayerIntMetric>
}
