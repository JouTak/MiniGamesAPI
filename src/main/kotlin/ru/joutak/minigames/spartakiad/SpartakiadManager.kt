package ru.joutak.minigames.spartakiad

import ru.joutak.minigames.MiniGamesPlugin
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
        participantsManager
            .reload()
            .thenCompose {
                val participants = participantsManager.getAll()
                return@thenCompose playerDataManager
                    .prefillParticipants(participants)
            }.thenAccept {
                MiniGamesPlugin.instance.logger.info("Информация об игроках успешно заполнена!")
            }.exceptionally { t ->
                MiniGamesPlugin.instance.logger.severe("Не удалось заполнить информацию об участниках: ${t.message}")
                MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
                return@exceptionally null
            }
    }

    fun getUuidResolver(): UuidResolver = uuidResolver

    fun getParticipantsManager(): ParticipantsManager = participantsManager

    fun getPlayerDataManager(): PlayerDataManager = playerDataManager

    override fun close() {
        playerDataManager.close()
        participantsManager.close()
    }
}
