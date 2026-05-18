package ru.joutak.minigames.integration.placeholderapi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.managers.MatchmakingManager

/**
 * %joutak_games_team_index%, %joutak_games_team_color%, %joutak_games_team_name%,
 * %joutak_games_mode_name%, %joutak_games_mode_display%.
 *
 * PAPI routes by splitting on the first '_', so the registered identifier must be "joutak".
 * Params arrive as "games_<name>" and we strip the prefix before dispatching.
 */
internal class MiniGamesPlaceholders(private val plugin: JavaPlugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "joutak"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ").ifEmpty { "JouTak" }

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (!params.startsWith("games_")) return null
        val p = params.removePrefix("games_")

        when (p.lowercase()) {
            "mode_name" -> return safe { MiniGamesAPI.config.get(ConfigKeys.MODE_NAME) } ?: ""
            "mode_display" -> return safe { MiniGamesAPI.config.get(ConfigKeys.MODE_DISPLAY_NAME) } ?: ""
        }

        val online = player?.player ?: return ""
        val style = safe { MiniGamesAPI.getCurrentTeamStyle(online) }

        if (style == null) {
            // TEMP diagnostic for empty placeholder bug.
            val uuid = online.uniqueId
            val active = MatchmakingManager.getActiveInstances()
            val started = active.filter { it.started }
            val mine = started.firstOrNull { it.hasActivePlayer(uuid) }
            MiniGamesCore.plugin.logger.info(
                "[joutak_games] empty for ${online.name} ($uuid) param=$p  " +
                    "active=${active.size} started=${started.size} hasActive=${mine != null}  " +
                    "teamIdx=${mine?.getActiveTeamIndex(uuid)}  lobbyIdx=${MiniGamesAPI.getPlayerTeamInLobby(online)}"
            )
        }

        return when (p.lowercase()) {
            "team_index" -> style?.teamNumber?.toString() ?: ""
            "team_color" -> style?.chatColor?.let { "&${it.char}" } ?: ""
            "team_name" -> style?.displayNamePlain ?: ""
            else -> null
        }
    }

    private inline fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()
}
