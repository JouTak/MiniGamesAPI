package ru.joutak.minigames.spartakiad

import ru.joutak.minigames.spartakiad.participant.ParticipantsManager
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider

class SpartakiadManager(
    private val participantsProvider: ParticipantsProvider,
) {
    private val participantsManager = ParticipantsManager(participantsProvider)
}
