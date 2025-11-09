package ru.joutak.minigames.domain

open class Player {
    val name: String
    var party: Team? = null
        private set

    constructor(name: String) {
        this.name = name
    }

    constructor(
        name: String,
        team: Team? = null,
    ) : this(name) {
        this.party = team
    }

    fun setParty(team: Team?) {
        this.party = team
    }
}
