package ru.joutak.minigames.spartakiad.playerData

import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.PlayerData
import ru.joutak.minigames.spartakiad.playerData.storage.PlayerDataStorage
import ru.joutak.minigames.util.uuid.UuidResolver
import java.util.*
import java.util.concurrent.CompletableFuture

class PlayerDataManager(
    private val playerDataStorage: PlayerDataStorage,
    private val uuidResolver: UuidResolver,
) {
    fun get(uuid: UUID): CompletableFuture<PlayerData?> =
        playerDataStorage.getPlayerData(
            uuid,
        )

    fun get(name: String): CompletableFuture<PlayerData?> =
        getUuid(name).thenCompose { uuid ->
            playerDataStorage.getPlayerData(uuid)
        }

    private fun getUuid(name: String): CompletableFuture<UUID> =
        CompletableFuture
            .supplyAsync {
                uuidResolver.getUuid(name) ?: throw NullPointerException("Не удалось получить UUID игрока $name!")
            }

    fun createIfNotExists(name: String): CompletableFuture<PlayerData> =
        getUuid(name).thenCompose { uuid ->
            playerDataStorage.createIfNotExists(
                uuid,
                name,
                MiniGamesCore.configuration.get(ConfigKeys.SPARTAKIAD_ATTEMPTS),
            )
        }

    fun decrementAttempt(uuid: UUID): CompletableFuture<Int?> = playerDataStorage.decrementAttempt(uuid)

    fun updateName(
        uuid: UUID,
        newName: String,
    ): CompletableFuture<Unit> =
        playerDataStorage.getPlayerData(uuid).thenCompose { playerData ->
            if (playerData == null) {
                CompletableFuture.failedFuture(
                    NullPointerException("Не удалось получить данные об игроке с UUID $uuid для обновления ника!"),
                )
            } else {
                if (playerData.name == newName) {
                    CompletableFuture.completedFuture(null)
                } else {
                    playerDataStorage.upsertPlayerData(playerData.copy(name = newName))
                }
            }
        }

    fun markPlayerWon(uuid: UUID): CompletableFuture<Unit> = playerDataStorage.markWon(uuid)

    fun hasPlayerWon(uuid: UUID): CompletableFuture<Boolean> = playerDataStorage.hasWon(uuid)

    fun close() {
        playerDataStorage.close()
    }
}
