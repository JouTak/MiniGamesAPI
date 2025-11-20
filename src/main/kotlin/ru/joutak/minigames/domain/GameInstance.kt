package ru.joutak.minigames.domain

import org.bukkit.entity.Player

class GameInstance(val config: GameInstanceConfig) {
    val teams = MutableList(config.teamCount) { mutableListOf<Player>() }

    fun addPlayer(player: Player): Boolean {
        val availableTeams = teams.filter { it.size < config.playersPerTeam }

        if (availableTeams.isEmpty()) {
            return false
        }
        val targetTeam = availableTeams.minByOrNull { it.size }

        if (targetTeam != null) {
            targetTeam.add(player)
            return true
        }

        return false
    }

    fun removePlayer(player: Player): Boolean {
        for (team in teams) {
            if (team.remove(player)) return true
        }
        return false
    }

    fun isFull(): Boolean =
        teams.all { it.size >= config.playersPerTeam }
}
