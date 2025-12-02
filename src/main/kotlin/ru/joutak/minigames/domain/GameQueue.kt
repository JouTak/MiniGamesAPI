package ru.joutak.minigames.domain

import ru.joutak.minigames.ui.QueueBossBarManager
import org.bukkit.entity.Player as BukkitPlayer
import java.util.*

object GameQueue {
    private val queue: MutableSet<UUID> = mutableSetOf()  // храним уникальные id игроков
    private val players: MutableMap<UUID, BukkitPlayer> = mutableMapOf()
    private val playerTeams: MutableMap<UUID, Team> = mutableMapOf()

    fun addPlayer(player: BukkitPlayer): Boolean {
        val added = queue.add(player.uniqueId)
        players[player.uniqueId] = player
        QueueBossBarManager.updateAll()
        return added
    }

    fun removePlayer(player: BukkitPlayer): Boolean {
        queue.remove(player.uniqueId)
        playerTeams[player.uniqueId]?.remove(player)
        playerTeams.remove(player.uniqueId)
        players.remove(player.uniqueId)
        QueueBossBarManager.updateAll()
        return true
    }

    fun assignPlayerToTeam(player: BukkitPlayer, team: Team) {
        playerTeams[player.uniqueId]?.remove(player)
        playerTeams[player.uniqueId] = team
        team.add(player)
    }

    fun getTeam(player: BukkitPlayer): Team? = playerTeams[player.uniqueId]

    fun getQueue(): List<BukkitPlayer> = queue.mapNotNull { players[it] }
}
