package ru.joutak.minigames.spartakiad.participant

import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import ru.joutak.minigames.util.uuid.UuidResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ParticipantsManager(
    private val participantsProvider: ParticipantsProvider,
    private val resolver: UuidResolver,
) : AutoCloseable {
    private val participants = ConcurrentHashMap<String, UUID>()

    private val lock = ReentrantReadWriteLock()

    init {
        reload()
    }

    fun reload() {
        val names = participantsProvider.load()
        val newParticipants = mutableMapOf<String, UUID>()

        for (name in names) {
            val name = name.trim()
            if (name.isBlank()) continue

            val uuid = resolver.getUuid(name) ?: continue

            newParticipants[name] = uuid
        }

        lock.write {
            participants.clear()
            participants.putAll(newParticipants)
        }
    }

    fun getAll(): Map<String, UUID> = lock.read { participants }

    fun contains(name: String): Boolean = participants.containsKey(name)

    fun get(name: String): UUID? = participants[name]

    @Synchronized
    fun add(name: String): Boolean {
        val preparedName = name.trim()
        if (preparedName.isBlank()) return false

        val uuid = resolver.getUuid(preparedName)
        if (uuid == null || participants.containsValue(uuid)) return false

        lock.write {
            participants[preparedName] = uuid
        }
        participantsProvider.save(getAll().keys)

        return true
    }

    @Synchronized
    fun remove(name: String) = lock.write { participants.remove(name) }

    override fun close() {
        participantsProvider.close()
    }
}
