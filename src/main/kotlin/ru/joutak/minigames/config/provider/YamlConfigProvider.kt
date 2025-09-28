package ru.joutak.minigames.config.provider

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKey
import java.io.File

class YamlConfigProvider(
    private val file: File,
) : ConfigProvider {
    private val yamlConfig: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    @Synchronized
    override fun get(path: String): Any? = yamlConfig.get(path)

    @Synchronized
    override fun set(
        path: String,
        value: Any?,
    ) {
        yamlConfig.set(path, value)
        saveToFile()
    }

    override fun contains(path: String): Boolean = yamlConfig.contains(path)

    override fun save(values: Map<ConfigKey<*>, Any>) {
        for ((key, value) in values) {
            yamlConfig.set(key.path, value)
        }
        saveToFile()
    }

    private fun saveToFile() {
        try {
            yamlConfig.save(file)
        } catch (e: Exception) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить конфиг: ${e.message}")
        }
    }
}
