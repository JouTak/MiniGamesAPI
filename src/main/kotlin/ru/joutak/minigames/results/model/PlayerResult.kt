package ru.joutak.minigames.results.model

import java.util.UUID

/**
 * Player-level result for a match.
 */
data class PlayerResult(
    val playerUuid: UUID,
    val playerName: String? = null,
    val teamId: Int? = null,
    val isWinner: Boolean = false,
    val joinedAtMs: Long? = null,
    val leftAtMs: Long? = null,
    val metrics: List<Metric> = emptyList(),
)
