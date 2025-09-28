package ru.joutak.minigames.spartakiad.participant

import org.bukkit.Bukkit
import ru.joutak.minigames.domain.Participant
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ParticipantsManager(
    private val provider: ParticipantsProvider,
) {
    private val uuidToParticipant = ConcurrentHashMap<UUID, Participant>()
    private val nameToUuid = ConcurrentHashMap<String, UUID>()

    private val lock = ReentrantReadWriteLock()

    init {
        reload()
    }

    fun reload() {
        val names = provider.load()
        val newByUuid = mutableMapOf<UUID, Participant>()
        val newNameToUuid = mutableMapOf<String, UUID>()

        for (rawName in names) {
            val name = rawName.trim()
            if (name.isEmpty()) continue

            val offline = Bukkit.getOfflinePlayer(name)
            val uuid = offline.uniqueId
            val actualName = offline.name ?: name

            val p = Participant(uuid, actualName)
            newByUuid[uuid] = p
            newNameToUuid[actualName.lowercase()] = uuid
        }

        lock.write {
            uuidToParticipant.clear()
            uuidToParticipant.putAll(newByUuid)
            nameToUuid.clear()
            nameToUuid.putAll(newNameToUuid)
        }
    }

    fun getAll(): List<Participant> = lock.read { uuidToParticipant.values.map { it } }

    fun containsUuid(uuid: UUID): Boolean = uuidToParticipant.containsKey(uuid)

    fun containsName(name: String): Boolean = nameToUuid.containsKey(name.lowercase())

    fun getByUuid(uuid: UUID): Participant? = uuidToParticipant[uuid]

    fun getByName(name: String): Participant? {
        val uuid = nameToUuid[name.lowercase()] ?: return null
        return uuidToParticipant[uuid]
    }

    /**
     * Добавляет по нику. Резолвит UUID и сохраняет в файл.
     * Возвращает true если добавлен (т.е. раньше не было).
     */
    @Synchronized
    fun addByName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false

        val offline = Bukkit.getOfflinePlayer(trimmed)
        val uuid = offline.uniqueId
        val actualName = offline.name ?: trimmed

        // Если уже есть по UUID — не добавляем
        if (uuidToParticipant.containsKey(uuid)) return false

        val p = Participant(uuid, actualName)
        uuidToParticipant[uuid] = p
        nameToUuid[actualName.lowercase()] = uuid

        // Сохраняем в провайдер список ников (в порядке current snapshot)
        provider.save(getAll().map { it.username })

        return true
    }

    /**
     * Удаляет участника по нику (или по UUID, если парсинг прошёл).
     */
    @Synchronized
    fun removeByNameOrUuid(identifier: String): Boolean {
        // пробуем распознать как UUID
        val uuid =
            try {
                UUID.fromString(identifier)
            } catch (e: IllegalArgumentException) {
                null
            }

        val removed =
            if (uuid != null) {
                // удаление по UUID
                val old = uuidToParticipant.remove(uuid)
                if (old != null) {
                    nameToUuid.remove(old.username.lowercase())
                    true
                } else {
                    false
                }
            } else {
                // удаление по нику
                val uid = nameToUuid.remove(identifier.lowercase()) ?: return false
                val old = uuidToParticipant.remove(uid) != null
                old
            }

        if (removed) {
            provider.save(getAll().map { it.username })
        }
        return removed
    }

    /**
     * Обновляем ник участника (например, при входе игрока на сервер).
     * Если UUID уже есть в списке, обновляем username и имя в YAML (чтобы админы видели актуальный ник).
     */
    @Synchronized
    fun updateName(
        uuid: UUID,
        newName: String,
    ) {
        val old = uuidToParticipant[uuid] ?: return
        if (old.username == newName) return

        // обновляем
        val updated = Participant(uuid, newName)
        uuidToParticipant[uuid] = updated

        // обновляем индекс по имени
        nameToUuid.remove(old.username.lowercase())
        nameToUuid[newName.lowercase()] = uuid

        provider.save(getAll().map { it.username })
    }
}
