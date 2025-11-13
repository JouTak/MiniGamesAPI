package ru.joutak.minigames.dto

import java.time.LocalDateTime
import java.util.*

data class GameResultDto(
    val gameUuid: UUID,
    val gameName: String,
    val participants: List<PlayerDto>,
    val winners: List<PlayerDto>,
    val dateTime: LocalDateTime,
    val results: Map<UUID, Int>
) {
    // Конструктор без даты — автоматически ставим текущую дату
    constructor(
        gameUuid: UUID,
        gameName: String,
        participants: List<PlayerDto>,
        winners: List<PlayerDto>,
        results: Map<UUID, Int>
    ) : this(gameUuid, gameName, participants, winners, LocalDateTime.now(), results)
}