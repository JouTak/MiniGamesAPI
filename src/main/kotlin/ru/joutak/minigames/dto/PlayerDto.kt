package ru.joutak.minigames.dto

import java.util.*

data class PlayerDto(
    val uuid: UUID?,
    val name: String,
    val teamName: String?
) {
    constructor(name: String) : this(null, name, null)

    constructor(name: String, teamName: String) : this(null, name, teamName)
}
