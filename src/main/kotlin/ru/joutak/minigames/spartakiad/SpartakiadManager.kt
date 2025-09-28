package ru.joutak.minigames.spartakiad

import ru.joutak.minigames.spartakiad.participant.ParticipantsManager
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import ru.joutak.minigames.util.uuid.UuidResolver

class SpartakiadManager(
    private val participantsProvider: ParticipantsProvider,
    private val uuidResolver: UuidResolver,
) {
    private val participantsManager = ParticipantsManager(participantsProvider, uuidResolver)

    fun getParticipantsManager(): ParticipantsManager = participantsManager
}
