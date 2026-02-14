package ru.joutak.minigames.results.model

import java.util.UUID

/**
 * Lightweight match snapshot for analytics (qualifiers, exports).
 * Contains teams with their metrics, without per-player data.
 */
data class MatchTeamsSnapshot(
    val matchId: UUID,
    val endedAtMs: Long,
    val teams: List<TeamMetricsSnapshot>,
)

data class TeamMetricsSnapshot(
    val teamId: Int,
    val placement: Int? = null,
    val isWinner: Boolean = false,
    val score: Double? = null,
    val metrics: List<Metric> = emptyList(),
)
