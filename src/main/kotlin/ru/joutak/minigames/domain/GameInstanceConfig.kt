package ru.joutak.minigames.domain

data class GameInstanceConfig(
    val id: String,
    val teamCount: Int,
    val playersPerTeam: Int,
    val meta: Map<String, Any> = emptyMap()
)
