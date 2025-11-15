// MiniGamesAPI.kt  
package ru.joutak.minigames

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.storage.YamlConfigStorage
import java.io.File

object MiniGamesAPI {
    private var plugin: JavaPlugin? = null
    private var config: Config? = null

    @JvmStatic
    fun initialize(plugin: JavaPlugin) {
        this.plugin = plugin

        // Создаем конфиг если его нет
        val configFile = File(plugin.dataFolder, "config.yml")
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false)
        }

        this.config = Config(YamlConfigStorage(configFile))
        plugin.logger.info("MiniGamesAPI инициализирован")
    }

    @JvmStatic
    fun getConfig(): Config {
        return config ?: throw IllegalStateException("MiniGamesAPI не инициализирован! Вызовите MiniGamesAPI.initialize(plugin)")
    }

    @JvmStatic
    fun getDataFolder(): File {
        return plugin?.dataFolder ?: throw IllegalStateException("MiniGamesAPI не инициализирован!")
    }

    @JvmStatic
    fun isInitialized(): Boolean = plugin != null && config != null
}