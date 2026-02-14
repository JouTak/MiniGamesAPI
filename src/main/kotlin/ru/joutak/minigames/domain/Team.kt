package ru.joutak.minigames.domain

import org.bukkit.entity.Player as BukkitPlayer

data class Team(
    val name: String,
    val members: MutableList<BukkitPlayer> = mutableListOf()
) {
    fun add(player: BukkitPlayer) {
        if (!members.contains(player)) {
            members.add(player)
        }
    }

    fun remove(player: BukkitPlayer) {
        members.remove(player)
    }
}
