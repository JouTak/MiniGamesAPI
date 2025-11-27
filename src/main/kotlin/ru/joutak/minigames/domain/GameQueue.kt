package ru.joutak.minigames.domain

import java.util.*
import org.bukkit.entity.Player as BukkitPlayer

object GameQueue {
    private val queue: MutableSet<UUID> = mutableSetOf()  // храним уникальные id игроков
    private val players: MutableMap<UUID, BukkitPlayer> = mutableMapOf()
    private val playerTeams: MutableMap<UUID, Team> = mutableMapOf()

    fun addPlayer(player: BukkitPlayer): Boolean {
        val added = queue.add(player.uniqueId)
        players[player.uniqueId] = player
        return added
    }

    fun removePlayer(player: BukkitPlayer): Boolean {
        queue.remove(player.uniqueId)
        playerTeams[player.uniqueId]?.remove(player)
        playerTeams.remove(player.uniqueId)
        players.remove(player.uniqueId)
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
