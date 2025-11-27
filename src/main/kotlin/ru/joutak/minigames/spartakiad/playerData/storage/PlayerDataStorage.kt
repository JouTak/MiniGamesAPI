package ru.joutak.minigames.spartakiad.playerData.storage

import ru.joutak.minigames.domain.PlayerData
import java.util.*
import java.util.concurrent.CompletableFuture

interface PlayerDataStorage : AutoCloseable {
    fun getPlayerData(uuid: UUID): CompletableFuture<PlayerData?>

    fun createIfNotExists(
        uuid: UUID,
        name: String,
        initialAttempts: Int,
    ): CompletableFuture<PlayerData>

    fun upsertPlayerData(playerData: PlayerData): CompletableFuture<Unit>

    fun updateAttempts(
        uuid: UUID,
        attempts: Int,
    ): CompletableFuture<Boolean>

    fun decrementAttempt(uuid: UUID): CompletableFuture<Int?>

    fun markWon(uuid: UUID): CompletableFuture<Unit>

    fun hasWon(uuid: UUID): CompletableFuture<Boolean>
}
