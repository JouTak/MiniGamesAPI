package ru.joutak.minigames.results.model

/**
 * Team-level result for a match.
 */
data class TeamResult(
    val teamId: Int,
    val placement: Int? = null,
    val isWinner: Boolean = false,
    val score: Double? = null,
    val metrics: List<Metric> = emptyList(),
    val completionStatus: CompletionStatus = CompletionStatus.FINISHED,
)
