package ru.joutak.minigames.spartakiad.whitelist

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.event.WhitelistReloadEvent
import ru.joutak.minigames.spartakiad.whitelist.storage.WhitelistStorage
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet

class WhitelistManager(
    private val whitelistStorage: WhitelistStorage,
) : Closeable {
    private val whitelist: MutableSet<String> = CopyOnWriteArraySet<String>()

    fun getAll(): Set<String> = whitelist

    fun contains(playerDto: PlayerDto): Boolean = whitelist.contains(playerDto.name)

    fun add(playerDto: PlayerDto): Boolean {
        if (playerDto.name.isBlank()) {
            return false
        }

        if (whitelist.contains(playerDto.name)) {
            return false
        }

        if (!whitelistStorage.add(playerDto)) {
            return false
        }

        whitelist.add(playerDto.name)
        return true
    }


    fun remove(playerDto: PlayerDto): Boolean {
        if (whitelistStorage.remove(playerDto)) {
            whitelist.remove(playerDto.name)
            return true
        }

        return false
    }

    fun clear() = whitelist.clear()

    fun reload(): CompletableFuture<Unit> =
        whitelistStorage
            .reload()
            .thenApply {
                clear()

                whitelist.addAll(whitelistStorage.getAll())

                Bukkit.getScheduler().runTask(
                    MiniGamesPlugin.instance,
                    Runnable {
                        Bukkit.getPluginManager().callEvent(WhitelistReloadEvent(getAll()))
                    },
                )
                MiniGamesPlugin.instance.logger.info("Список участников был перезагружен!")
            }

    override fun close() {
        whitelistStorage.close()
    }
}
