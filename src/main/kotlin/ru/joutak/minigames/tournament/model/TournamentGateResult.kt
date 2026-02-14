package ru.joutak.minigames.tournament.model

data class TournamentGateResult(
    val allowed: Boolean,
    val teamKey: String? = null,
    val denyReason: TournamentDenyReason? = null,
)

enum class TournamentDenyReason {
    NOT_PARTICIPANT,
    NO_ATTEMPTS,
    WINNER,
    NOT_QUALIFIED,
    ERROR,
}
