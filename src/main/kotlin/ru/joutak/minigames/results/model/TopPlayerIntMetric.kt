package ru.joutak.minigames.results.model

import java.util.UUID

data class TopPlayerIntMetric(
    val matchId: UUID,
    val playerUuid: UUID,
    val playerName: String? = null,
    val value: Long,
)
