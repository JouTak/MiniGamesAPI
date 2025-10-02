package ru.joutak.minigames.config.provider

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKey
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class YamlConfigProvider(
    private val configFile: File,
    private val debounceMillis: Long = 200L,
    private val closeTimeoutMillis: Long = 5000L,
) : ConfigProvider {
    private val configRef: AtomicReference<YamlConfiguration> = AtomicReference(YamlConfiguration.loadConfiguration(configFile))

    @Volatile
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yaml-config-writer-${configFile.name}").apply { isDaemon = true }
        }

    @Synchronized
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

    @Synchronized
    override fun contains(path: String): Boolean {
        val yamlConfig = configRef.get()
        return yamlConfig.contains(path)
    }

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
            }, debounceMillis, TimeUnit.MILLISECONDS)
    }

    private fun saveToFile(yamlConfig: YamlConfiguration) {
        try {
            yamlConfig.save(configFile)
        } catch (e: Exception) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить конфиг: ${e.message}")
            MiniGamesPlugin.instance.logger.severe(e.stackTraceToString())
        }
    }

    override fun close() {
        val future =
            executor.submit {
                val yamlConfig = configRef.get()
                pendingSaveFuture?.cancel(false)
                saveToFile(yamlConfig)
            }
        try {
            future.get(closeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить конфиг: ${t.message}")
            MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
        } finally {
            executor.shutdown()
        }
    }
}
