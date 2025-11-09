package ru.joutak.minigames.domain

import java.util.*

class Participant(
    val uuid: UUID,
    name: String,
    val attempts: Int,
    val won: Boolean = false,
    var team: Team? = null
) : Player(name, team) {

}
