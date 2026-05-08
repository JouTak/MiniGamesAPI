package ru.joutak.minigames.integration.placeholderapi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.config.ConfigKeys

/**
 * %joutak_games_team_index%, %joutak_games_team_color%, %joutak_games_team_name%,
 * %joutak_games_mode_name%, %joutak_games_mode_display%.
 */
internal class MiniGamesPlaceholders(private val plugin: JavaPlugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "joutak_games"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ").ifEmpty { "JouTak" }

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        when (params.lowercase()) {
            "mode_name" -> return safe { MiniGamesAPI.config.get(ConfigKeys.MODE_NAME) } ?: ""
            "mode_display" -> return safe { MiniGamesAPI.config.get(ConfigKeys.MODE_DISPLAY_NAME) } ?: ""
        }

        val online = player?.player ?: return ""
        val style = safe { MiniGamesAPI.getCurrentTeamStyle(online) }

        return when (params.lowercase()) {
            "team_index" -> style?.teamNumber?.toString() ?: ""
            "team_color" -> style?.chatColor?.let { "&${it.char}" } ?: ""
            "team_name" -> style?.displayNamePlain ?: ""
            else -> null
        }
    }

    private inline fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()
}
