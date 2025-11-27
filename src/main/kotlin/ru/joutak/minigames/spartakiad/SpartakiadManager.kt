package ru.joutak.minigames.spartakiad

import ru.joutak.minigames.spartakiad.participant.ParticipantManager
import ru.joutak.minigames.spartakiad.participant.storage.ParticipantStorage
import ru.joutak.minigames.spartakiad.whitelist.WhitelistManager
import ru.joutak.minigames.spartakiad.whitelist.storage.WhitelistStorage
import ru.joutak.minigames.util.uuid.UuidResolver
import java.nio.file.Path

class SpartakiadManager(
    val gameDataPath: Path,
    playerDataProvider: ParticipantStorage,
    whitelistStorage: WhitelistStorage,
    uuidResolver: UuidResolver,
) : AutoCloseable {
    val whitelistManager = WhitelistManager(whitelistStorage)
    val participantManager = ParticipantManager(playerDataProvider, uuidResolver)

    init {
        whitelistManager.reload()
    }

    override fun close() {
        participantManager.close()
        whitelistManager.close()
    }
}
