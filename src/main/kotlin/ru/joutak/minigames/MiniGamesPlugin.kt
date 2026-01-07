package ru.joutak.minigames

import org.bukkit.plugin.java.JavaPlugin

class MiniGamesPlugin : JavaPlugin() {
    override fun onEnable() {
        MiniGamesCore.initialize(this)
        logger.info("MiniGamesPlugin enabled")
    }

    override fun onDisable() {
        try {
            MiniGamesCore.shutdown()
        } catch (t: Throwable) {
            logger.warning("Ошибка при выключении MiniGamesCore: ${t.message}")
        }
    }
}
