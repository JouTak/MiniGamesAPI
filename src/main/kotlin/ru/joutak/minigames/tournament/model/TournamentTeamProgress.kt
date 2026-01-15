package ru.joutak.minigames.tournament.model

data class TournamentTeamProgress(
    val eventId: String,
    val stage: String,
    val teamKey: String,
    val attemptsLeft: Int,
    val won: Boolean,
)
