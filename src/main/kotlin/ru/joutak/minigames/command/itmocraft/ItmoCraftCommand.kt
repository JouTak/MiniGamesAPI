package ru.joutak.minigames.command.itmocraft

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.tournament.advance.TournamentAdvanceManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager

/**
 * Public ITMOcraft command.
 *
 * - /itmocraft rating ... is available for regular players.
 * - Admin tournament/qualifier tools remain available for admins under the same root.
 */
object ItmoCraftCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    private fun send(sender: CommandSender, message: Component) {
        // Paper has both sendMessage(Component) and sendMessage(ComponentLike) which is ambiguous for Kotlin.
        sender.sendMessage(message as ComponentLike)
    }

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        val plugin = MiniGamesCore.plugin
        val adminPerm = "minigames.tournament.admin"

        return Commands.literal("itmocraft")
            .executes { ctx ->
                send(ctx.source.sender, Component.text("/itmocraft rating top <limit> [all]", NamedTextColor.GRAY))
                send(ctx.source.sender, Component.text("/itmocraft rating me [all]", NamedTextColor.GRAY))
                send(ctx.source.sender, Component.text("/itmocraft rating team <team_key> [all]", NamedTextColor.GRAY))
                Command.SINGLE_SUCCESS
            }
            .then(buildRatingPublicNode())
            .then(buildQualifierNode().requires { it.sender.hasPermission(adminPerm) })
            .then(buildExportNode().requires { it.sender.hasPermission(adminPerm) })
            .then(buildAdvanceNode(plugin).requires { it.sender.hasPermission(adminPerm) })
    }

    private fun buildRatingPublicNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("rating")
            .then(
                Commands.literal("top")
                    .executes { ctx ->
                        val res = TournamentQualifierManager.getRatingLines(limit = 20, includeIncomplete = false)
                        for (line in res) send(ctx.source.sender, line)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                            .executes { ctx ->
                                val limit = IntegerArgumentType.getInteger(ctx, "limit")
                                val res = TournamentQualifierManager.getRatingLines(limit = limit, includeIncomplete = false)
                                for (line in res) send(ctx.source.sender, line)
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.literal("all")
                                    .executes { ctx ->
                                        val limit = IntegerArgumentType.getInteger(ctx, "limit")
                                        val res = TournamentQualifierManager.getRatingLines(limit = limit, includeIncomplete = true)
                                        for (line in res) send(ctx.source.sender, line)
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
                    .then(
                        Commands.literal("all")
                            .executes { ctx ->
                                val res = TournamentQualifierManager.getRatingLines(limit = 20, includeIncomplete = true)
                                for (line in res) send(ctx.source.sender, line)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("me")
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        if (sender !is Player) {
                            send(sender, Component.text("Only players can use /itmocraft rating me", NamedTextColor.RED))
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val teamKey = TournamentManager.getCachedTeamKey(sender.uniqueId)
                        if (teamKey.isNullOrBlank()) {
                            send(sender, Component.text("No team_key for you (not in tournament roster?)", NamedTextColor.RED))
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val res = TournamentQualifierManager.getTeamStandingLines(teamKey, includeIncomplete = false)
                        for (line in res) send(sender, line)
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.literal("all")
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                if (sender !is Player) {
                                    send(sender, Component.text("Only players can use /itmocraft rating me", NamedTextColor.RED))
                                    return@executes Command.SINGLE_SUCCESS
                                }
                                val teamKey = TournamentManager.getCachedTeamKey(sender.uniqueId)
                                if (teamKey.isNullOrBlank()) {
                                    send(sender, Component.text("No team_key for you (not in tournament roster?)", NamedTextColor.RED))
                                    return@executes Command.SINGLE_SUCCESS
                                }
                                val res = TournamentQualifierManager.getTeamStandingLines(teamKey, includeIncomplete = true)
                                for (line in res) send(sender, line)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("team")
                    .then(
                        Commands.argument("team_key", StringArgumentType.word())
                            .executes { ctx ->
                                val teamKey = StringArgumentType.getString(ctx, "team_key")
                                val res = TournamentQualifierManager.getTeamStandingLines(teamKey, includeIncomplete = false)
                                for (line in res) send(ctx.source.sender, line)
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.literal("all")
                                    .executes { ctx ->
                                        val teamKey = StringArgumentType.getString(ctx, "team_key")
                                        val res = TournamentQualifierManager.getTeamStandingLines(teamKey, includeIncomplete = true)
                                        for (line in res) send(ctx.source.sender, line)
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
    }

    private fun buildQualifierNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("qualifier")
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        for (line in TournamentQualifierManager.statusLines()) send(ctx.source.sender, line)
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        TournamentQualifierManager.reload()
                        send(ctx.source.sender, Component.text("Qualifier config reloaded", NamedTextColor.GREEN))
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("lock")
                    .executes { ctx ->
                        val ok = TournamentQualifierManager.lock()
                        send(
                            ctx.source.sender,
                            if (ok) Component.text("Qualifier locked", NamedTextColor.GREEN)
                            else Component.text("Failed to lock qualifier (see console)", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("unlock")
                    .executes { ctx ->
                        val ok = TournamentQualifierManager.unlock()
                        send(
                            ctx.source.sender,
                            if (ok) Component.text("Qualifier unlocked", NamedTextColor.GREEN)
                            else Component.text("Failed to unlock qualifier (see console)", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("recalc")
                    .executes { ctx ->
                        if (TournamentQualifierManager.isRecalcInFlight()) {
                            send(ctx.source.sender, Component.text("Recalc already running", NamedTextColor.RED))
                            return@executes Command.SINGLE_SUCCESS
                        }

                        TournamentQualifierManager.recalcAsync()
                        send(ctx.source.sender, Component.text("Recalc started", NamedTextColor.YELLOW))
                        Command.SINGLE_SUCCESS
                    }
            )
    }

    private fun buildExportNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("export")
            .then(
                Commands.literal("rating")
                    .executes { ctx ->
                        val res = TournamentQualifierManager.exportRating()
                        send(
                            ctx.source.sender,
                            if (res.ok) Component.text("Exported rating to ${res.path}", NamedTextColor.GREEN)
                            else Component.text("Export failed: ${res.message}", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("audit")
                    .executes { ctx ->
                        val res = TournamentQualifierManager.exportAudit()
                        send(
                            ctx.source.sender,
                            if (res.ok) Component.text("Exported audit to ${res.path}", NamedTextColor.GREEN)
                            else Component.text("Export failed: ${res.message}", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
    }

    private fun buildAdvanceNode(plugin: org.bukkit.plugin.java.JavaPlugin): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("advance")
            .then(
                Commands.literal("auto")
                    .executes { ctx ->
                        val res = TournamentAdvanceManager.advanceAuto(plugin)
                        send(ctx.source.sender, if (res.ok) Component.text(res.message, NamedTextColor.GREEN) else Component.text(res.message, NamedTextColor.RED))
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("top")
                    .then(
                        Commands.argument("take", IntegerArgumentType.integer(0, 512))
                            .executes { ctx ->
                                val take = IntegerArgumentType.getInteger(ctx, "take")
                                val res = TournamentAdvanceManager.advanceTop(plugin, take)
                                send(ctx.source.sender, if (res.ok) Component.text(res.message, NamedTextColor.GREEN) else Component.text(res.message, NamedTextColor.RED))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        val file = TournamentAdvanceManager.load(plugin)
                        if (file == null) {
                            send(ctx.source.sender, Component.text("No advanced_teams.yml", NamedTextColor.DARK_GRAY))
                            return@executes Command.SINGLE_SUCCESS
                        }

                        send(
                            ctx.source.sender,
                            Component.text(
                                "Advanced teams: event=${file.eventId} from=${file.fromStage} to=${file.toStage} take=${file.take} generated_at=${file.generatedAtMs}",
                                NamedTextColor.GREEN,
                            )
                        )
                        val preview = file.teams.take(30)
                        if (preview.isNotEmpty()) {
                            send(ctx.source.sender, Component.text(preview.joinToString(", "), NamedTextColor.GRAY))
                        }
                        if (file.teams.size > preview.size) {
                            send(ctx.source.sender, Component.text("... +${file.teams.size - preview.size} more", NamedTextColor.DARK_GRAY))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("clear")
                    .executes { ctx ->
                        val ok = TournamentAdvanceManager.clear(plugin)
                        send(
                            ctx.source.sender,
                            if (ok) Component.text("advanced_teams.yml cleared", NamedTextColor.GREEN)
                            else Component.text("Failed to clear advanced_teams.yml", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
    }
}
