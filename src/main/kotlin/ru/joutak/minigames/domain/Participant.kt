package ru.joutak.minigames.domain

import java.util.UUID

data class Participant(
    val uuid: UUID,
    val username: String,
)
