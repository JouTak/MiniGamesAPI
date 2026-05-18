package ru.joutak.minigames.integration.placeholderapi

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

object PlaceholderApiIntegration {

    @Volatile
    private var registered: Boolean = false

    fun tryInitialize(plugin: JavaPlugin) {
        if (registered) return
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return

        val expansion = MiniGamesPlaceholders(plugin)
        if (expansion.register()) {
            registered = true
            plugin.logger.info("[MiniGamesAPI] PlaceholderAPI expansion '${expansion.identifier}' registered")
        } else {
            plugin.logger.warning("[MiniGamesAPI] PlaceholderAPI expansion failed to register")
        }
    }
}
