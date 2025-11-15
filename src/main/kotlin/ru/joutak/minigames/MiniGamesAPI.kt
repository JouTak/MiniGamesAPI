package ru.joutak.minigames

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import java.io.File

object MiniGamesAPI {
    lateinit var plugin: JavaPlugin
        internal set

    lateinit var config: Config
        internal set

    fun initialize(plugin: JavaPlugin, config: Config) {
        this.plugin = plugin
        this.config = config
    }

    // Добавьте этот метод для совместимости
    fun getDataFolder(): File = plugin.dataFolder
}