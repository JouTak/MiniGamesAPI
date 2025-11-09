package ru.joutak.minigames.spartakiad.participant.storage

import ru.joutak.minigames.domain.Participant
import java.io.Closeable
import java.util.*
import java.util.concurrent.CompletableFuture

interface ParticipantStorage : Closeable {
    fun getParticipant(uuid: UUID): CompletableFuture<Participant?>

    fun createIfNotExists(
        uuid: UUID,
        name: String,
        initialAttempts: Int,
    ): CompletableFuture<Participant>

    fun updateParticipant(participant: Participant): CompletableFuture<Unit>

    fun updateAttempts(
        uuid: UUID,
        attempts: Int,
    ): CompletableFuture<Boolean>

    fun decrementAttempt(uuid: UUID): CompletableFuture<Int?>

    fun markWon(uuid: UUID): CompletableFuture<Unit>

    fun hasWon(uuid: UUID): CompletableFuture<Boolean>
}
