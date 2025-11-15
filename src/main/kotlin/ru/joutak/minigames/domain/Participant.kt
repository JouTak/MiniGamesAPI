package ru.joutak.minigames.domain

import java.util.UUID

data class Participant(
    val uuid: UUID,
    val name: String,
    val attempts: Int,
    val won: Boolean = false,
    val team: Team? = null
)
