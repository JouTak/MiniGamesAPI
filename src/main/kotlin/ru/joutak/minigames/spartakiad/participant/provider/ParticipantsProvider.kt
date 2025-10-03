package ru.joutak.minigames.spartakiad.participant.provider

import java.util.concurrent.CompletableFuture

interface ParticipantsProvider : AutoCloseable {
    fun getAll(): List<String>

    fun save(participants: Collection<String>)

    fun reload(): CompletableFuture<Unit>
}
