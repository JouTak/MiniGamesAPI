package ru.joutak.minigames.domain

import org.bukkit.entity.Player as BukkitPlayer

object GameQueue {
    private val queue: MutableList<Player> = mutableListOf()
    private val players: MutableMap<BukkitPlayer, Player> = mutableMapOf()

    fun getOrCreatePlayer(bukkitPlayer: BukkitPlayer): Player {
        return players.getOrPut(bukkitPlayer) { Player(bukkitPlayer.name) }
    }

    fun addPlayer(player: Player): Boolean {
        return if (!queue.contains(player)) {
            queue.add(player)
            true
        } else false
    }

    fun removePlayer(player: Player): Boolean {
        return queue.remove(player)
    }

    fun getQueue(): List<Player> = queue.toList()
}
