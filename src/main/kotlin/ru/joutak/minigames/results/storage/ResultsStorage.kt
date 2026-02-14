package ru.joutak.minigames.results.storage

import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.MatchTeamsSnapshot
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

    /**
     * Loads stored matches (filtered by tournament event+stage) with teams and their metrics.
     * Intended for analytics (qualifier Elo, exports).
     *
     * Ordering MUST be stable for deterministic recalculation: ended_at_ms ASC, match_id ASC.
     */
    fun loadMatchTeamsWithMetrics(
        eventId: String,
        stage: String,
        endedAtMaxInclusive: Long? = null,
        limit: Int = 500,
        offset: Int = 0,
    ): List<MatchTeamsSnapshot>

    /**
     * Returns ended_at_ms for a stored match, or null if not found.
     */
    fun getMatchEndedAtMs(matchId: UUID): Long?
}
