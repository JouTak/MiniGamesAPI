package ru.joutak.minigames.ui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import java.util.UUID

/**
 * Lobby-only sidebar scoreboard.
 *
 * Visible to all players in lobby (i.e., not participating in started matches).
 * Shows teams + their current rosters for the next (or player's) match.
 */
object LobbyScoreboardManager {

    private const val OBJECTIVE_NAME = "mgLobby"

    private data class BoardState(
        val board: Scoreboard,
        val objective: Objective,
        val previous: Scoreboard,
        var lastEntries: Set<String> = emptySet()
    )

    private val states = mutableMapOf<UUID, BoardState>()
    private var taskId: Int? = null

    fun start() {
        if (taskId != null) return
        taskId = Bukkit.getScheduler().runTaskTimer(
            MiniGamesCore.plugin,
            Runnable { updateAll() },
            20L,
            10L
        ).taskId
    }

    fun stop() {
        taskId?.let { Bukkit.getScheduler().cancelTask(it) }
        taskId = null

        Bukkit.getOnlinePlayers().forEach { remove(it) }
        states.clear()
    }

    fun updateAll() {
        // remove stale states
        states.keys.removeIf { Bukkit.getPlayer(it) == null }

        Bukkit.getOnlinePlayers().forEach { p ->
            if (MatchmakingManager.isPlayerInStartedGame(p.uniqueId)) {
                remove(p)
            } else {
                ensure(p)
                update(p)
            }
        }
    }

    fun ensure(player: Player) {
        if (states.containsKey(player.uniqueId)) return

        val mgr = Bukkit.getScoreboardManager() ?: return
        val previous = player.scoreboard
        val board = mgr.newScoreboard

        @Suppress("DEPRECATION")
        val objective = board.registerNewObjective(
            OBJECTIVE_NAME,
            "dummy",
            colorize(Messages.getString("ui.lobby.scoreboard.title") ?: "&bITMOcraft &fminiGAMES")
        )
        objective.displaySlot = DisplaySlot.SIDEBAR

        states[player.uniqueId] = BoardState(board, objective, previous)
        player.scoreboard = board
    }

    fun remove(player: Player) {
        val state = states.remove(player.uniqueId) ?: return
        if (player.scoreboard == state.board) {
            player.scoreboard = state.previous
        }
    }

    private fun update(player: Player) {
        val state = states[player.uniqueId] ?: return
        if (player.scoreboard != state.board) {
            // another plugin replaced scoreboard; do not fight
            states.remove(player.uniqueId)
            return
        }

        val lines = buildLines(player)

        // Simple + robust: rebuild entries each update.
        state.lastEntries.forEach { state.board.resetScores(it) }
        val newEntries = LinkedHashSet<String>()

        var score = lines.size
        lines.forEachIndexed { idx, raw ->
            val entry = uniqueEntry(colorize(raw), idx)
            newEntries.add(entry)
            state.objective.getScore(entry).score = score
            score--
        }

        state.lastEntries = newEntries
    }

    private fun buildLines(player: Player): List<String> {
        val modeDisplay = MiniGamesCore.configuration.get(ConfigKeys.MODE_DISPLAY_NAME)
        val placeholders = mapOf(
            "mode_display" to modeDisplay,
            "nick" to player.name
        )

        val lines = mutableListOf<String>()

        lines += formatLine(
            key = "ui.lobby.scoreboard.lines.server",
            fallback = "&7Вы играете на &bITMOcraft &fminiGAMES",
            placeholders = placeholders
        )
        lines += formatLine(
            key = "ui.lobby.scoreboard.lines.mode",
            fallback = "&7Режим: &e{mode_display}",
            placeholders = placeholders
        )
        lines += formatLine(
            key = "ui.lobby.scoreboard.lines.nick",
            fallback = "&7Ваш ник: &a{nick}",
            placeholders = placeholders
        )
        lines += formatLine(
            key = "ui.lobby.scoreboard.lines.ip",
            fallback = "&7IP: &6craft.itmo.ru",
            placeholders = placeholders
        )
        lines += "&8 "

        val instance = selectInstanceFor(player)
        if (instance == null) {
            lines += formatLine(
                key = "ui.lobby.scoreboard.empty",
                fallback = "&7Нет набора игроков",
                placeholders = placeholders
            )
            return trimToSidebarLimit(lines)
        }

        val maxPerTeam = instance.config.playersPerTeam
        instance.teams.forEachIndexed { index, team ->
            val teamNumber = index + 1
            val teamName = Messages.getString("ui.teamselect.teams.$teamNumber.name")
                ?: "&fКоманда $teamNumber"

            lines += "$teamName &7(${team.size}/$maxPerTeam)"

            val membersLine = if (team.isEmpty()) {
                "&8- &7пусто"
            } else {
                "&8- &f" + team.joinToString(", ") { it.name }.limitVisible(32)
            }
            lines += membersLine
        }

        return trimToSidebarLimit(lines)
    }

    private fun selectInstanceFor(player: Player): GameInstance? {
        val instances = MatchmakingManager.getActiveInstances().filter { !it.started }
        if (instances.isEmpty()) return null

        // Prefer the instance where this player is queued.
        val mine = instances.firstOrNull { inst ->
            inst.teams.any { t -> t.any { it.uniqueId == player.uniqueId } }
        }
        if (mine != null) return mine

        // Otherwise show the instance with most waiting players.
        return instances.maxByOrNull { it.teams.sumOf { team -> team.size } }
    }

    private fun formatLine(key: String, fallback: String, placeholders: Map<String, String>): String {
        val raw = Messages.getString(key) ?: fallback
        var out = raw
        placeholders.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out
    }

    private fun trimToSidebarLimit(lines: List<String>): List<String> {
        if (lines.size <= 15) return lines
        return lines.take(14) + "&7..."
    }

    private fun colorize(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun uniqueEntry(colored: String, idx: Int): String {
        val codes = ChatColor.values()
        val tail = codes[(idx + 1) % codes.size].toString()
        return colored + tail
    }

    /**
     * Cuts a string by *visible* length (ignoring \u00A7 color codes).
     */
    private fun String.limitVisible(maxVisible: Int): String {
        var visible = 0
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            val c = this[i]
            if (c == ChatColor.COLOR_CHAR && i + 1 < this.length) {
                // keep color code
                sb.append(c).append(this[i + 1])
                i += 2
                continue
            }
            if (visible >= maxVisible) break
            sb.append(c)
            visible++
            i++
        }
        if (i < this.length) sb.append("…")
        return sb.toString()
    }
}
