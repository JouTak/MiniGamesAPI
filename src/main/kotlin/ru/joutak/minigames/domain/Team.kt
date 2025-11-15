package ru.joutak.minigames.domain

import kotlinx.serialization.Serializable

@Serializable
data class Team(
    val name: String,
    val members: MutableList<Player> = mutableListOf()
) {
    fun add(player: Player) {
        members.add(player)
    }

    fun remove(player: Player) {
        members.remove(player)
    }
}
