package ru.joutak.minigames.config.storage

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKey
import ru.joutak.minigames.config.ConfigKeys
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class YamlConfigStorage(
    private val configFile: File,
) : ConfigStorage {
    private val configRef: AtomicReference<YamlConfiguration> =
        AtomicReference(YamlConfiguration.loadConfiguration(configFile))

    @Volatile
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yaml-config-writer-${configFile.name}").apply { isDaemon = true }
        }

    override fun reload(): CompletableFuture<Unit> =
        CompletableFuture
            .supplyAsync {
                try {
                    val yamlParticipants = YamlConfiguration.loadConfiguration(configFile)
                    configRef.set(yamlParticipants)
                    // Bukkit.getPluginManager().callEvent(FileReloadedEvent(getAll()))
                } catch (t: Throwable) {
                    MiniGamesPlugin.instance.logger.severe("Не удалось загрузить файл с конфигом: ${t.message}")
                    configRef.set(YamlConfiguration())
                }
            }

    override fun get(path: String): Any? {
        val yamlConfig = configRef.get()
        return yamlConfig.get(path)
    }

    @Synchronized
    override fun set(
        path: String,
        value: Any?,
    ) {
        executor.execute {
            val yamlConfig = configRef.get()
            yamlConfig.set(path, value)
            scheduleSave(yamlConfig)
        }
    }

    override fun contains(path: String): Boolean {
        val yamlConfig = configRef.get()
        return yamlConfig.contains(path)
    }

    @Synchronized
    override fun save(values: Map<ConfigKey<*>, Any>) {
        executor.execute {
            val yamlConfig = configRef.get()
            for ((key, value) in values) {
                yamlConfig.set(key.path, value)
            }
            scheduleSave(yamlConfig)
        }
    }

    private fun scheduleSave(yamlConfig: YamlConfiguration) {
        pendingSaveFuture?.cancel(false)
        pendingSaveFuture =
            executor.schedule({
                saveToFile(yamlConfig)
            }, MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS), TimeUnit.MILLISECONDS)
    }

    private fun saveToFile(yamlConfig: YamlConfiguration) {
        try {
            yamlConfig.save(configFile)
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить конфиг: ${t.message}")
            MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
        }
    }

    override fun close() {
        val future =
            executor.submit {
                if (pendingSaveFuture != null) {
                    val yamlConfig = configRef.get()
                    pendingSaveFuture?.cancel(false)
                    saveToFile(yamlConfig)
                }
            }
        try {
            future.get(
                MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_CLOSE_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS
            )
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить конфиг: ${t.message}")
            MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
        } finally {
            executor.shutdown()
        }
    }
}
