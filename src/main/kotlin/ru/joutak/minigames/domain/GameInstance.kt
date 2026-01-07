package ru.joutak.minigames.domain

import org.bukkit.entity.Player
import ru.joutak.minigames.ui.QueueBossBarManager

class GameInstance(val config: GameInstanceConfig) {
    val teams = MutableList(config.teamCount) { mutableListOf<Player>() }

    @Volatile
    var started: Boolean = false

    fun addPlayer(player: Player): Boolean {
        if (started) return false

        val availableTeams = teams.filter { it.size < config.playersPerTeam }
        if (availableTeams.isEmpty()) return false

        val targetTeam = availableTeams.minByOrNull { it.size }
        if (targetTeam != null) {
            targetTeam.add(player)
            QueueBossBarManager.updateAll()
            return true
        }

        return false
    }

    fun removePlayer(player: Player): Boolean {
        for (team in teams) {
            if (team.remove(player)) {
                // Если инстанс полностью опустел, считаем что он снова доступен для очереди.
                if (teams.all { it.isEmpty() }) {
                    started = false
                }
                return true
            }
        }

        return false
    }

    fun isFull(): Boolean =
        teams.all { it.size >= config.playersPerTeam }
}
