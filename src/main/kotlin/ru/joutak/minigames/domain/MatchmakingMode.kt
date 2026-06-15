package ru.joutak.minigames.domain

/**
 * How players are arranged during lobby matchmaking.
 *
 * TEAM - normal team games: players can choose a team, each team can contain playersPerTeam players.
 * SOLO - solo games: manual team selection is disabled, every player occupies a separate technical slot.
 */
enum class MatchmakingMode {
    TEAM,
    SOLO;

    companion object {
        fun from(raw: Any?): MatchmakingMode {
            val value = raw?.toString()?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == value } ?: TEAM
        }
    }
}
