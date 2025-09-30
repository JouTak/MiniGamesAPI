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
    private val gameDataPath: Path,
    private val playerDataProvider: PlayerDataStorage,
    private val participantsProvider: ParticipantsProvider,
    private val uuidResolver: UuidResolver,
) : AutoCloseable {
    private val participantsManager = ParticipantsManager(participantsProvider, uuidResolver)
    private val playerDataManager = PlayerDataManager(playerDataProvider)

    init {
        val participants = participantsManager.getAll()
        playerDataManager
            .prefillParticipants(participants)
            .thenAccept {
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
