package ru.joutak.minigames.domain

import java.util.concurrent.CopyOnWriteArraySet

class Team(val name: String, vararg players: Player) {
    private val _members: MutableSet<Player> = CopyOnWriteArraySet<Player>()

    val members: Set<Player>
        get() = _members

    init {
        _members.addAll(players)
    }

    fun add(player: Player) {
        _members.add(player)
    }

    fun remove(player: Player) {
        _members.remove(player)
    }
}