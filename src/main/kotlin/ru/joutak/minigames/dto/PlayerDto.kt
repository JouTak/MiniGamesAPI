package ru.joutak.minigames.dto

import java.util.*

data class PlayerDto(
    val uuid: UUID?,
    val name: String,
    val teamName: String?,
    val attemptsLeft: Int//todo сделать чтение из конфига
) {
    constructor(name: String) : this(null, name, null, 1)

    constructor(name: String, teamName: String) : this(null, name, teamName, 1)
}
