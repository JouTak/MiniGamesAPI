package ru.joutak.minigames.spartakiad.participant

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.ParticipantsListReloadEvent
import ru.joutak.minigames.spartakiad.participant.provider.ParticipantsProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet

class ParticipantsManager(
    private val participantsProvider: ParticipantsProvider,
) : AutoCloseable {
    private val participants = CopyOnWriteArraySet<String>()

    fun getAll(): Iterable<String> = participants

    fun contains(name: String): Boolean = participants.contains(name)

    @Throws(IllegalStateException::class)
    fun add(name: String) {
        val preparedName = name.trim()
        if (preparedName.isBlank()) {
            throw IllegalArgumentException("Недопустимое имя у игрока $name!")
        }

        if (participants.contains(preparedName)) {
            throw IllegalArgumentException("Игрок с именем $preparedName уже есть в списке!")
        }

        participants.add(name)
        participantsProvider.add(name)
    }

    fun remove(name: String) {
        participants.remove(name)
        participantsProvider.remove(name)
    }

    fun clear() = participants.clear()

    fun reload(): CompletableFuture<Unit> =
        participantsProvider
            .reload()
            .thenApply {
                val names = participantsProvider.getAll()
                clear()

                names.forEach { name ->
                    try {
                        add(name)
                    } catch (e: IllegalArgumentException) {
                        MiniGamesPlugin.instance.logger.warning("Не удалось добавить участника $name: ${e.message}")
                    }
                }

                Bukkit.getScheduler().runTask(
                    MiniGamesPlugin.instance,
                    Runnable {
                        Bukkit.getPluginManager().callEvent(ParticipantsListReloadEvent(getAll()))
                    },
                )
                MiniGamesPlugin.instance.logger.info("Список участников был перезагружен!")
            }

    override fun close() {
        participantsProvider.close()
    }
}
