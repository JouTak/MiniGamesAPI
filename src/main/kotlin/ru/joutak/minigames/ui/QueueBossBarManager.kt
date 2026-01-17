package ru.joutak.minigames.ui

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import kotlin.math.max

/**
 * Global lobby queue BossBar.
 *
 * IMPORTANT: should be visible to all players in lobby (i.e. not in started matches).
 */
object QueueBossBarManager {

    private val bar: BossBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)

    /** Players we currently show the bar to. */
    private val shownTo = mutableSetOf<java.util.UUID>()

    fun ensure(player: Player) {
        if (!player.isOnline) return

        // First call: compute title/progress so players don't see an empty bar.
        if (bar.name() == Component.empty()) {
            updateAll()
        }

        if (isLobbyPlayer(player)) {
            show(player)
        } else {
            remove(player)
        }
    }

    fun add(player: Player) = ensure(player)

    fun remove(player: Player) {
        if (!shownTo.remove(player.uniqueId)) return
        try {
            player.hideBossBar(bar)
        } catch (_: Throwable) {
        }
    }

    fun update(player: Player) {
        updateAll()
    }

    fun updateAll() {
        val instances = MatchmakingManager.getActiveInstances().filter { !it.started }

        // Pick the instance with the most waiting players — represents "closest to start".
        val best = instances.maxByOrNull { inst ->
            inst.teams.sumOf { it.size }
        }

        val waiting = best?.teams?.sumOf { it.size } ?: 0
        val maxPlayers = best?.let { max(1, it.config.teamCount * it.config.playersPerTeam) } ?: 1

        val placeholders = mapOf(
            "current" to waiting.toString(),
            "max" to maxPlayers.toString(),
            "free" to instances.count { it.teams.sumOf { t -> t.size } == 0 }.toString(),
            "total" to instances.size.toString()
        )

        val title = if (instances.isEmpty()) {
            // No instances loaded at all.
            if (Messages.has("messages.bossbar.queue.no_instances")) {
                Messages.prefixedComponent("messages.bossbar.queue.no_instances")
            } else {
                Messages.prefixedComponent("messages.ready.no_free_arenas")
            }
        } else {
            if (Messages.has("messages.bossbar.queue.title")) {
                Messages.prefixedComponent("messages.bossbar.queue.title", placeholders)
            } else {
                // Fallback text.
                Messages.prefixedComponent("messages.join.help", placeholders)
            }
        }

        val progress = (waiting.toFloat() / maxPlayers.toFloat()).coerceIn(0f, 1f)

        bar.name(title)
        bar.progress(progress)

        // Show to all lobby players, hide from match participants.
        Bukkit.getOnlinePlayers().forEach { p ->
            if (isLobbyPlayer(p)) {
                show(p)
            } else {
                remove(p)
            }
        }
    }

    private fun isLobbyPlayer(player: Player): Boolean {
        return player.isOnline && player.gameMode != GameMode.SPECTATOR && !MatchmakingManager.isPlayerInStartedGame(player.uniqueId)
    }

    private fun show(player: Player) {
        if (shownTo.add(player.uniqueId)) {
            try {
                player.showBossBar(bar)
            } catch (_: Throwable) {
            }
        }
    }
}