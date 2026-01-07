package ru.joutak.minigames.domain

import org.bukkit.entity.Player as BukkitPlayer
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.UUID

object GameQueue {
    private val queue: MutableSet<UUID> = mutableSetOf()
    private val players: MutableMap<UUID, BukkitPlayer> = mutableMapOf()
    private val playerTeams: MutableMap<UUID, Team> = mutableMapOf()

    fun addPlayer(player: BukkitPlayer): Boolean {
        val added = queue.add(player.uniqueId)
        players[player.uniqueId] = player
        QueueBossBarManager.updateAll()
        return added
    }

    fun removePlayer(player: BukkitPlayer): Boolean {
        val uuid = player.uniqueId

        val removedFromQueue = queue.remove(uuid)
        val hadPlayerRef = players.remove(uuid) != null

        val team = playerTeams.remove(uuid)
        team?.remove(player)

        if (removedFromQueue || hadPlayerRef || team != null) {
            QueueBossBarManager.updateAll()
        }

        return removedFromQueue
    }

    fun assignPlayerToTeam(player: BukkitPlayer, team: Team) {
        playerTeams[player.uniqueId]?.remove(player)
        playerTeams[player.uniqueId] = team
        team.add(player)
    }

    fun getTeam(player: BukkitPlayer): Team? = playerTeams[player.uniqueId]

    fun getQueue(): List<BukkitPlayer> = queue.mapNotNull { players[it] }
}
