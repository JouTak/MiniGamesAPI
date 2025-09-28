package ru.joutak.minigames.spartakiad.participant

import org.bukkit.Bukkit
import ru.joutak.minigames.domain.Participant
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import ru.joutak.minigames.util.uuid.UuidResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ParticipantsManager(
    private val provider: ParticipantsProvider,
    private val resolver: UuidResolver,
) {
    private val participants = ConcurrentHashMap<UUID, Participant>()

    private val lock = ReentrantReadWriteLock()

    init {
        reload()
    }

    fun reload() {
        val names = provider.load()
        val newParticipants = mutableMapOf<UUID, Participant>()

        for (name in names) {
            val name = name.trim()
            if (name.isBlank()) continue

            val uuid = resolver.resolveByName(name)

            val p = Participant(uuid, name)
            newParticipants[uuid] = p
        }

        lock.write {
            participants.clear()
            participants.putAll(newParticipants)
        }
    }

    fun getAll(): List<Participant> = lock.read { participants.values.map { it } }

    fun contains(uuid: UUID): Boolean = participants.containsKey(uuid)

    fun get(uuid: UUID): Participant? = participants[uuid]

    fun get(name: String): Participant? = participants.values.firstOrNull { it.username == name }

    @Synchronized
    fun add(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false

        val uuid = resolver.resolveByName(name)
        if (participants.containsKey(uuid)) return false

        val p = resolver.getParticipant(uuid)
        participants[uuid] = p

        provider.save(getAll().map { it.username })

        return true
    }

    @Synchronized
    fun remove(uuid: UUID): Boolean {
        val removed = participants.remove(uuid) != null
        if (removed) provider.save(getAll().map { it.username })
        return removed
    }

    @Synchronized
    fun remove(name: String): Boolean {
        val uuid = resolver.resolveByName(name)
        return remove(uuid)
    }
}
