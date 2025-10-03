package ru.joutak.minigames.spartakiad.playerData

import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.PlayerData
import ru.joutak.minigames.spartakiad.playerData.storage.PlayerDataStorage
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PlayerDataManager(
    private val playerDataStorage: PlayerDataStorage,
) {
    fun getPlayerData(
        uuid: UUID,
        name: String,
    ): CompletableFuture<PlayerData?> =
        playerDataStorage.getPlayerData(
            uuid,
        )

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

    fun prefillParticipants(participants: Map<String, UUID>): CompletableFuture<Unit> {
        val futures =
            participants
                .map { p ->
                    playerDataStorage.createIfNotExists(
                        p.value,
                        p.key,
                        MiniGamesPlugin.instance.getConfiguration().get(ConfigKeys.SPARTAKIAD_ATTEMPTS),
                    )
                }.toTypedArray()

        return CompletableFuture.allOf(*futures).thenApply { }
    }

    fun markPlayerWon(uuid: UUID): CompletableFuture<Unit> = playerDataStorage.markWon(uuid)

    fun hasPlayerWon(uuid: UUID): CompletableFuture<Boolean> = playerDataStorage.hasWon(uuid)

    fun close() {
        playerDataStorage.close()
    }
}
