package ru.joutak.minigames.domain

data class GameInstanceConfig(
    val id: String,
    val teamCount: Int,
    val playersPerTeam: Int,
    val meta: Map<String, Any> = emptyMap(),
    val matchmakingMode: MatchmakingMode = MatchmakingMode.TEAM,
) {
    /** Total amount of players this instance can accept. */
    val maxPlayers: Int
        get() = (teamCount * playersPerTeam).coerceAtLeast(1)

    /**
     * Amount of waiting groups stored in [GameInstance.teams].
     * In SOLO mode these are technical one-player slots, not user-visible teams.
     */
    val waitingGroupCount: Int
        get() = if (isSoloMode) maxPlayers else teamCount.coerceAtLeast(1)

    /** Max players in a waiting group. In SOLO mode every slot contains one player. */
    val waitingGroupCapacity: Int
        get() = if (isSoloMode) 1 else playersPerTeam.coerceAtLeast(1)

    val isSoloMode: Boolean
        get() = matchmakingMode == MatchmakingMode.SOLO
}
