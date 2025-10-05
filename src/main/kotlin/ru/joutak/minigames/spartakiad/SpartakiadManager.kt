package ru.joutak.minigames.spartakiad

import ru.joutak.minigames.spartakiad.participant.ParticipantsManager
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import ru.joutak.minigames.spartakiad.playerData.PlayerDataManager
import ru.joutak.minigames.spartakiad.playerData.storage.PlayerDataStorage
import ru.joutak.minigames.util.uuid.UuidResolver
import java.lang.AutoCloseable
import java.nio.file.Path

class SpartakiadManager(
    val gameDataPath: Path,
    playerDataProvider: PlayerDataStorage,
    participantsProvider: ParticipantsProvider,
    uuidResolver: UuidResolver,
) : AutoCloseable {
    val participantsManager = ParticipantsManager(participantsProvider)
    val playerDataManager = PlayerDataManager(playerDataProvider, uuidResolver)

    init {
        participantsManager.reload()
    }

    override fun close() {
        playerDataManager.close()
        participantsManager.close()
    }
}
