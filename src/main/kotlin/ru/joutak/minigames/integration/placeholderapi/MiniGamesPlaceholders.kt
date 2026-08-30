package ru.joutak.minigames.integration.placeholderapi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager

/**
 * %joutak_games_team_index%, %joutak_games_team_color%, %joutak_games_team_name%,
 * %joutak_games_mode_name%, %joutak_games_mode_display%,
 * %joutak_games_tournament_name%, %joutak_games_elo_top_1%, %joutak_games_games_top_1%.
 *
 * PAPI routes by splitting on the first '_', so the registered identifier must be "joutak".
 * Params arrive as "games_<name>" and we strip the prefix before dispatching.
 */
internal class MiniGamesPlaceholders(private val plugin: JavaPlugin) : PlaceholderExpansion() {

    private companion object {
        const val NO_ACTIVE_TOURNAMENT = "Сейчас нет активного турнира"
        const val EMPTY_RATING = "Рейтинг пока пуст"
        val TOP_PLACEHOLDER = Regex("^(elo|games)_top_([1-9]\\d*)$")
    }

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

        tournamentPlaceholder(p.lowercase())?.let { return it }

        val online = player?.player ?: return ""
        val style = safe { MiniGamesAPI.getCurrentTeamStyle(online) }

        return when (p.lowercase()) {
            "team_index" -> style?.teamNumber?.toString() ?: ""
            "team_color" -> style?.chatColor?.let { "&${it.char}" } ?: ""
            "team_name" -> style?.displayNamePlain ?: ""
            else -> null
        }
    }

    private fun tournamentPlaceholder(param: String): String? {
        val topMatch = TOP_PLACEHOLDER.matchEntire(param)
        if (param != "tournament_name" && topMatch == null) return null

        if (safe { TournamentManager.isEloTournamentMode() } != true) {
            return NO_ACTIVE_TOURNAMENT
        }

        if (param == "tournament_name") {
            val configuredName = safe { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_DISPLAY_NAME) }
                ?.trim()
                .orEmpty()
            if (configuredName.isNotEmpty()) return configuredName

            return safe { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_EVENT_ID) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: NO_ACTIVE_TOURNAMENT
        }

        val kind = topMatch!!.groupValues[1]
        val rank = topMatch.groupValues[2].toIntOrNull() ?: return ""
        val snapshot = TournamentQualifierManager.getSnapshot() ?: return EMPTY_RATING

        val currentEventId = safe { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_EVENT_ID) }?.trim().orEmpty()
        val currentStage = safe { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_STAGE) }?.trim().orEmpty()
        if (snapshot.eventId != currentEventId || snapshot.stage != currentStage) return EMPTY_RATING

        val minMatches = TournamentQualifierManager.getConfig().minMatches
        val rows = snapshot.rows.filter { it.completedMatches >= minMatches }

        if (rows.isEmpty()) return EMPTY_RATING
        val row = rows.getOrNull(rank - 1) ?: return ""
        val displayName = TournamentManager.getParticipantDisplayName(row.teamKey)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: row.teamKey

        return when (kind) {
            "elo" -> "$displayName — ${row.eloRating} ELO"
            "games" -> formatGames(row.completedMatches)
            else -> ""
        }
    }

    private fun formatGames(count: Int): String {
        val word = when {
            count % 100 in 11..14 -> "игр"
            count % 10 == 1 -> "игра"
            count % 10 in 2..4 -> "игры"
            else -> "игр"
        }
        return "$count $word"
    }

    private inline fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()
}
