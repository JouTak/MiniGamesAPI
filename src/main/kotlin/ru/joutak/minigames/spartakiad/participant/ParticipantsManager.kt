package ru.joutak.minigames.spartakiad.participant

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.ParticipantsListReloadedEvent
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import ru.joutak.minigames.util.uuid.UuidResolver
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ParticipantsManager(
    private val participantsProvider: ParticipantsProvider,
    private val resolver: UuidResolver,
) : AutoCloseable {
    private val participants = ConcurrentHashMap<String, UUID>()

    fun reload(): CompletableFuture<Unit> {
        return participantsProvider
            .reload()
            .thenCompose {
                val names = participantsProvider.getAll()
                clear()

                val futures =
                    names
                        .map { name ->
                            add(name).exceptionally { cause ->
                                MiniGamesPlugin.instance.logger.warning("Не удалось добавить участника $name: ${cause.message}")
                            }
                        }.toTypedArray()

                return@thenCompose CompletableFuture.allOf(*futures)
            }.thenApply {
                Bukkit.getScheduler().runTask(
                    MiniGamesPlugin.instance,
                    Runnable {
                        Bukkit.getPluginManager().callEvent(ParticipantsListReloadedEvent(getAll().keys))
                    },
                )
                MiniGamesPlugin.instance.logger.info("Список участников был перезагружен!")
            }
    }

    fun getAll(): Map<String, UUID> = participants

    fun contains(name: String): Boolean = participants.containsKey(name)

    fun get(name: String): UUID? = participants[name]

    @Synchronized
    @Throws(IllegalArgumentException::class)
    fun add(name: String): CompletableFuture<Unit> {
        return CompletableFuture
            .supplyAsync {
                val preparedName = name.trim()
                if (preparedName.isBlank()) {
                    throw IllegalArgumentException("Недопустимое имя у игрока $name!")
                }

                val uuid =
                    resolver.getUuid(preparedName)
                        ?: throw IllegalArgumentException("Не удалось получить UUID игрока $preparedName!")

                if (participants.containsValue(uuid)) {
                    throw IllegalArgumentException("Игрок с именем $preparedName уже есть в списке!")
                }

                return@supplyAsync Pair(preparedName, uuid)
            }.thenAccept { pair ->
                add(pair.first, pair.second)
            }.thenApply {}
    }

    private fun add(
        name: String,
        uuid: UUID,
    ) {
        participants[name] = uuid
        participantsProvider.save(getAll().keys)
    }

    fun remove(name: String) = participants.remove(name)

    fun clear() = participants.clear()

    override fun close() {
        participantsProvider.close()
    }
}
