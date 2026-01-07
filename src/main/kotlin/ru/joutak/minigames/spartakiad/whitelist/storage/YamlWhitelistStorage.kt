package ru.joutak.minigames.spartakiad.whitelist.storage

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.event.WhitelistChangeEvent
import ru.joutak.minigames.storage.FileStorageWatcher
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class YamlWhitelistStorage(private val whitelistFile: File) : WhitelistStorage {
    private val lock = Any()
    private val whitelistRef = AtomicReference<YamlConfiguration>(YamlConfiguration())

    @Volatile
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yaml-whitelist-writer-${whitelistFile.name}").apply { isDaemon = true }
        }

    private val whitelistFileWatcher: FileStorageWatcher = FileStorageWatcher(whitelistFile, this) {
        Bukkit.getScheduler().runTask(
            MiniGamesCore.plugin,
            Runnable {
                Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                    Bukkit.getPluginManager().callEvent(WhitelistChangeEvent())
                })
            },
        )
    }

    @Volatile
    private var lastSavedAt: Long = System.currentTimeMillis()

    private val yamlKey = "participants"

    override fun getLastSavedAt(): Long = lastSavedAt

    override fun getAll(): Set<String> {
        synchronized(lock) {
            val whitelistYaml = whitelistRef.get()
            if (!whitelistYaml.contains(yamlKey)) {
                MiniGamesCore.plugin.logger.warning("Не найден ключ $yamlKey в файле с участниками ${whitelistFile.name}!")
                return emptySet()
            }

            return whitelistYaml.getStringList(yamlKey)
                .map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
    }

    override fun contains(playerDto: PlayerDto): Boolean {
        return getAll().contains(playerDto.name)
    }

    override fun add(playerDto: PlayerDto): Boolean {
        synchronized(lock) {
            if (contains(playerDto)) {
                return false
            }

            val whitelistYaml = whitelistRef.get()
            val whitelist = whitelistYaml.getStringList(yamlKey)
            whitelist.add(playerDto.name)
            whitelistYaml.set(yamlKey, whitelist)
            scheduleSave(whitelistYaml)
            return true
        }
    }

    override fun remove(playerDto: PlayerDto): Boolean {
        synchronized(lock) {
            if (!contains(playerDto)) {
                return false
            }

            val whitelistYaml = whitelistRef.get()
            val whitelist = whitelistYaml.getStringList(yamlKey)
            whitelist.remove(playerDto.name)
            whitelistYaml.set(yamlKey, whitelist)
            scheduleSave(whitelistYaml)
            return true
        }
    }

    private fun scheduleSave(whitelistYaml: YamlConfiguration) {
        pendingSaveFuture?.cancel(false)
        pendingSaveFuture =
            executor.schedule({
                saveToFile(whitelistYaml)
            }, MiniGamesCore.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS), TimeUnit.MILLISECONDS)
        lastSavedAt = System.currentTimeMillis()
    }

    private fun saveToFile(whitelistYaml: YamlConfiguration) {
        try {
            synchronized(lock) {
                whitelistYaml.save(whitelistFile)
                lastSavedAt = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            MiniGamesCore.plugin.logger.severe("Не удалось сохранить список участников: ${t.message}")
            MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
        }
    }

    override fun reload(): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync {
            try {
                val yamlParticipants = YamlConfiguration.loadConfiguration(whitelistFile)
                whitelistRef.set(yamlParticipants)
            } catch (t: Throwable) {
                MiniGamesCore.plugin.logger.severe("Не удалось загрузить файл со списком участников: ${t.message}")
                whitelistRef.set(YamlConfiguration())
            }
        }

    override fun close() {
        whitelistFileWatcher.close()

        val future =
            executor.submit {
                if (pendingSaveFuture != null) {
                    val yamlParticipants = whitelistRef.get()
                    pendingSaveFuture?.cancel(false)
                    saveToFile(yamlParticipants)
                }
            }
        try {
            future.get(
                MiniGamesCore.configuration.get(ConfigKeys.STORAGE_CLOSE_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS
            )
        } catch (t: Throwable) {
            MiniGamesCore.plugin.logger.severe("Не удалось сохранить список участников: ${t.message}")
            MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
        } finally {
            executor.shutdown()
        }
    }
}
