package ru.joutak.spartakiad

import org.bukkit.plugin.java.JavaPlugin

class SpartakiadManager : JavaPlugin() {
    companion object {
        @JvmStatic
        lateinit var instance: SpartakiadManager
    }

    /**
     * Plugin startup logic
     */
    override fun onEnable() {
        instance = this

        logger.info("Плагин ${pluginMeta.name} версии ${pluginMeta.version} включен!")
    }

    /**
     * Plugin shutdown logic
     */
    override fun onDisable() {
    }
}
