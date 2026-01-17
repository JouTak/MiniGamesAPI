package ru.joutak.minigames.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.tournament.TournamentManager

object PlayerJoinListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Tournament: reserve/online team cap (4x4x4x4). Strict mode reserves slots on prelogin.
        val cfg = MiniGamesCore.configuration
        val tournamentEnabled = cfg.get(ConfigKeys.TOURNAMENT_ENABLED)
        val preloginStrict = cfg.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)
        if (tournamentEnabled && preloginStrict) {
            val bypassPerm = cfg.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION).trim()
            val bypassPermOk = bypassPerm.isNotBlank() && player.hasPermission(bypassPerm)
            val bypassUuidOk = TournamentManager.isBypassUuid(player.uniqueId)

            if (bypassPermOk || bypassUuidOk) {
                TournamentManager.releasePending(player.uniqueId)
            } else {
                // Move reserved slot -> online.
                val teamKey = TournamentManager.finalizeJoinFromPending(player.uniqueId)
                if (teamKey == null) {
                    // Fallback: should not happen, but keep it async to avoid blocking main thread.
                    val uuid = player.uniqueId
                    val name = player.name

                    Bukkit.getScheduler().runTaskAsynchronously(MiniGamesCore.plugin, Runnable {
                        val gate = TournamentManager.checkAccess(uuid, name)
                        val resolvedTeamKey = gate.teamKey

                        Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                            val p = Bukkit.getPlayer(uuid) ?: return@Runnable
                            if (!p.isOnline) return@Runnable

                            if (!gate.allowed) {
                                TournamentManager.releasePending(uuid)
                                p.kick(TournamentManager.denyKickMessageComponent(gate.denyReason))
                                return@Runnable
                            }

                            if (resolvedTeamKey == null) {
                                TournamentManager.releasePending(uuid)
                                p.kick(TournamentManager.denyKickMessageComponent(null))
                                return@Runnable
                            }

                            if (TournamentManager.isTeamOnlineFull(resolvedTeamKey)) {
                                TournamentManager.releasePending(uuid)
                                p.kick(TournamentManager.teamFullOnlineKickMessageComponent())
                                return@Runnable
                            }

                            TournamentManager.registerOnline(uuid, resolvedTeamKey)
                        })
                    })
                }
            }
        }

        // НЕ используем player.scheduler.* (EntityScheduler): на Purpur это уходит в MinecraftInternalPlugin
        // и начинает флудить UnsupportedOperationException в ServerSchedulerReportingWrapper.
        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (!player.isOnline) return@Runnable

            val cfg2 = MiniGamesCore.configuration
            val tournamentEnabled2 = cfg2.get(ConfigKeys.TOURNAMENT_ENABLED)
            val preloginStrict2 = cfg2.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)

            if (tournamentEnabled2 && !preloginStrict2) {
                val bypassPerm = cfg2.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION).trim()
                val bypassPermOk = bypassPerm.isNotBlank() && player.hasPermission(bypassPerm)
                val bypassUuidOk = TournamentManager.isBypassUuid(player.uniqueId)

                if (!bypassPermOk && !bypassUuidOk) {
                    val gate = TournamentManager.checkAccess(player.uniqueId, player.name)
                    if (!gate.allowed) {
                        TournamentManager.releasePending(player.uniqueId)
                        player.kick(TournamentManager.denyKickMessageComponent(gate.denyReason))
                        return@Runnable
                    }

                    val teamKey = gate.teamKey
                    if (teamKey != null) {
                        if (TournamentManager.isTeamOnlineFull(teamKey)) {
                            TournamentManager.releasePending(player.uniqueId)
                            player.kick(TournamentManager.teamFullOnlineKickMessageComponent())
                            return@Runnable
                        }
                        TournamentManager.registerOnline(player.uniqueId, teamKey)
                    }
                } else {
                    TournamentManager.releasePending(player.uniqueId)
                }
            }

            val helpKey = if (tournamentEnabled2) "messages.join.help_tournament" else "messages.join.help"

            if (Messages.has(helpKey)) {
                player.sendMessage(Messages.prefixedComponent(helpKey))
                return@Runnable
            }

            // Fallback for old installations without messages config.
            player.sendMessage(
                Component.text("Основные команды:\n", NamedTextColor.YELLOW)
                    .append(
                        Component.text(
                            "/ready - быстро присоединиться к первой свободной команде\n",
                            NamedTextColor.YELLOW
                        )
                    )
                    .append(
                        Component.text(
                            "/teamselect - выбрать команду (или используйте предмет в хотбаре)\n",
                            NamedTextColor.YELLOW
                        )
                    )
                    .append(Component.text("/unready - выйти из очереди/ожидания\n", NamedTextColor.YELLOW))
                    .append(Component.text("/lobby - вернуться в лобби миниигр", NamedTextColor.YELLOW))
            )
        }, 20L)
    }
}
