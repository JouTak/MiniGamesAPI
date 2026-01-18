package ru.joutak.minigames.tournament.advance

data class AdvancedTeamsFile(
    val eventId: String,
    val fromStage: String,
    val toStage: String,
    val generatedAtMs: Long,
    val take: Int,
    val teams: List<String>,
)
