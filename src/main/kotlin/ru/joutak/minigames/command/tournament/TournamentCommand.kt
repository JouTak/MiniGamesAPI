package ru.joutak.minigames.command.tournament

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.tournament.advance.TournamentAdvanceManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager

object TournamentCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    private fun send(sender: CommandSender, message: Component) {
        // Paper has both sendMessage(Component) and sendMessage(ComponentLike) which is ambiguous for Kotlin.
        sender.sendMessage(message as ComponentLike)
    }

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        val plugin = MiniGamesCore.plugin
        return Commands.literal("tournament")
            .requires { it.sender.hasPermission("minigames.tournament.admin") }
            .then(buildQualifierNode())
            .then(buildRatingNode())
            .then(buildExportNode())
            .then(buildAdvanceNode(plugin))
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

    private fun buildRatingNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("rating")
            .executes { ctx ->
                val res = TournamentQualifierManager.getRatingLines(limit = 30, includeIncomplete = false)
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
                        Commands.literal("includeIncomplete")
                            .executes { ctx ->
                                val limit = IntegerArgumentType.getInteger(ctx, "limit")
                                val res = TournamentQualifierManager.getRatingLines(limit = limit, includeIncomplete = true)
                                for (line in res) send(ctx.source.sender, line)
                                Command.SINGLE_SUCCESS
                            }
                    )
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
