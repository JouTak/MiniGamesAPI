// PlayerFileCheckListener.kt
package ru.joutak.minigames.listener

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.config.ConfigKeys
import net.kyori.adventure.text.Component
import java.io.File
import java.nio.file.Files
import java.util.stream.Collectors

object PlayerFileCheckListener : Listener {

    // Автоматическая регистрация при загрузке класса
    init {
        try {
            // Ищем любой плагин, который зависит от нашей библиотеки
            val plugins = Bukkit.getPluginManager().plugins
            val dependentPlugin = plugins.find { plugin ->
                plugin.description.depend.contains("MiniGamesAPI") ||
                        plugin.description.softDepend.contains("MiniGamesAPI")
            } as? JavaPlugin

            if (dependentPlugin != null) {
                Bukkit.getPluginManager().registerEvents(this, dependentPlugin)
                dependentPlugin.logger.info("MiniGamesAPI PlayerFileCheckListener автоматически зарегистрирован")
            }
        } catch (e: Exception) {
            // Молча игнорируем, если не можем авто-зарегистрироваться
        }
    }

    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        // Используем MiniGamesPlugin для конфигурации, если доступен
        val config = try {
            ru.joutak.minigames.MiniGamesPlugin.instance.configuration
        } catch (e: Exception) {
            // Если MiniGamesPlugin не доступен, используем конфиг через API
            MiniGamesAPI.getConfig()
        }

        val fileName = config.get(ConfigKeys.TEAM_PLAYER_FILE)
        val dataFolder = try {
            ru.joutak.minigames.MiniGamesPlugin.instance.dataFolder
        } catch (e: Exception) {
            MiniGamesAPI.getDataFolder()
        }

        val file = File(dataFolder, fileName)

        if (!file.exists()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Файл игроков не найден!"))
            return
        }

        val players = try {
            Files.lines(file.toPath())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .collect(Collectors.toSet())
        } catch (e: Exception) {
            emptySet<String>()
        }

        if (!players.contains(event.name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                Component.text("Ваш ник не в списке игроков!"))
        }
    }
}