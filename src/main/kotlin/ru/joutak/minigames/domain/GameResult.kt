package ru.joutak.minigames.domain

import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class GameResult(
    val gameUuid: UUID,
    val gameName: String,
    val participants: List<Player>,
    val winners: List<Player>,
    val dateTime: LocalDateTime = LocalDateTime.now(),
    val results: Map<UUID, Int>
)

