package ru.joutak.minigames.ui

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.managers.MatchmakingManager
import java.util.*

object QueueBossBarManager {

    private val bars = mutableMapOf<UUID, BossBar>()

    fun updateFor(player: Player) {
        val instance = MatchmakingManager.getActiveInstances().firstOrNull { inst ->
            inst.teams.flatten().any { it.uniqueId == player.uniqueId }
        } ?: return

        val total = instance.config.teamCount * instance.config.playersPerTeam
        val current = instance.teams.sumOf { it.size }

        val progress = current.toFloat() / total.toFloat()

        val bar = bars.computeIfAbsent(player.uniqueId) {
            BossBar.bossBar(
                Component.text("Ожидание игроков..."),
                progress,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10
            )
        }

        bar.name(
            Component.text("Готовы: $current / $total", NamedTextColor.YELLOW)
        )
        bar.progress(progress)

        player.showBossBar(bar)
    }

    fun updateAll() {
        Bukkit.getOnlinePlayers().forEach { updateFor(it) }
    }

    fun remove(player: Player) {
        bars[player.uniqueId]?.let { player.hideBossBar(it) }
        bars.remove(player.uniqueId)
    }
}
