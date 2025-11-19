package ru.joutak.minigames.domain

import org.bukkit.entity.Player

class GameInstance(val config: GameInstanceConfig) {
    val teams = MutableList(config.teamCount) { mutableListOf<Player>() }

    fun addPlayer(player: Player): Boolean {
        for (team in teams) {
            if (team.size < config.playersPerTeam) {
                team.add(player)
                return true
            }
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
