package ru.joutak.minigames.integration.voicechat

import de.maxhenkel.voicechat.api.BukkitVoicechatService
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys

/**
 * Facade that wires SimpleVoiceChat integration into MiniGamesAPI.
 *
 * Loaded only when SVC plugin is detected at runtime — see the dispatcher in
 * [ru.joutak.minigames.MiniGamesCore.loadDependencies]. All references to
 * `de.maxhenkel.*` live in this package, so JVM never tries to resolve them
 * on servers that don't ship SVC.
 */
object VoiceChatIntegration {

    @Volatile
    private var initialized: Boolean = false

    fun tryInitialize(plugin: JavaPlugin, config: Config) {
        if (initialized) return
        if (Bukkit.getPluginManager().getPlugin("voicechat") == null) return
        if (!config.get(ConfigKeys.VOICECHAT_ENABLED)) {
            plugin.logger.info("[MiniGamesAPI] SimpleVoiceChat detected but voicechat.enabled=false — integration skipped")
            return
        }

        val service = plugin.server.servicesManager.load(BukkitVoicechatService::class.java)
        if (service == null) {
            plugin.logger.warning("[MiniGamesAPI] BukkitVoicechatService is not registered — SVC integration disabled")
            return
        }

        service.registerPlugin(VoiceChatHook)
        Bukkit.getPluginManager().registerEvents(VoiceChatListener, plugin)
        initialized = true
        plugin.logger.info("[MiniGamesAPI] SimpleVoiceChat integration enabled")
    }
}
