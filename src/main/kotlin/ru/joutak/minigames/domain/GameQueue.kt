package ru.joutak.minigames.domain

import org.bukkit.entity.Player as BukkitPlayer

object GameQueue {
    private val queue: MutableList<Player> = mutableListOf()
    private val players: MutableMap<BukkitPlayer, Player> = mutableMapOf()
    private val playerTeams: MutableMap<Player, Team> = mutableMapOf()

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
        queue.remove(player)
        // Убираем игрока из команды
        playerTeams[player]?.remove(player)
        playerTeams.remove(player)
        return true
    }

    fun assignPlayerToTeam(player: Player, team: Team) {
        // Если игрок уже в другой команде, убираем его
        playerTeams[player]?.remove(player)
        playerTeams[player] = team
        team.add(player)
    }

    fun getTeam(player: Player): Team? = playerTeams[player]

    fun getQueue(): List<Player> = queue.toList()
}
