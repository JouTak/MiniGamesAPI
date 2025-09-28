package ru.joutak.minigames.util.uuid

import ru.joutak.minigames.domain.Participant
import java.util.UUID

interface UuidResolver {
    fun resolveByName(name: String): UUID

    fun getParticipant(uuid: UUID): Participant
}
