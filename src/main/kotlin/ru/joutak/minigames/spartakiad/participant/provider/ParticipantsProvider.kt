package ru.joutak.minigames.spartakiad.participant.provider

import java.util.concurrent.CompletableFuture

interface ParticipantsProvider : AutoCloseable {
    fun getLastSavedAt(): Long

    fun getAll(): List<String>

    fun contains(name: String): Boolean

    fun add(name: String): Boolean

    fun remove(name: String): Boolean

    fun save(participants: Collection<String>)

    fun reload(): CompletableFuture<Unit>
}
