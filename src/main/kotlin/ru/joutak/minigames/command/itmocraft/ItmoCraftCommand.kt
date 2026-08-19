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
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.tournament.advance.TournamentAdvanceManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager
import ru.joutak.minigames.tournament.plan.TournamentMatchPlanManager
import ru.joutak.minigames.tournament.seeding.TournamentSeedingManager
import java.io.File

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
            .then(buildRosterNode().requires { it.sender.hasPermission(adminPerm) })
            .then(buildPlanNode(plugin).requires { it.sender.hasPermission(adminPerm) })
            .then(buildSeedNode(plugin).requires { it.sender.hasPermission(adminPerm) })
            .then(buildAdvanceNode(plugin).requires { it.sender.hasPermission(adminPerm) })
    }

    private fun buildRosterNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("roster")
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        val players = Bukkit.getOnlinePlayers().toList()
                        val byTeam = players
                            .filterNot { TournamentManager.isBypassUuid(it.uniqueId) }
                            .groupBy { TournamentManager.getCachedTeamKey(it.uniqueId) ?: "<unknown>" }
                            .toList()
                            .sortedWith(compareByDescending<Pair<String, List<Player>>> { it.second.size }.thenBy { it.first })

                        send(ctx.source.sender, Component.text("Online roster cache: ${players.size} players", NamedTextColor.GRAY))
                        for ((teamKey, list) in byTeam.take(20)) {
                            send(ctx.source.sender, Component.text("$teamKey: ${list.size}", NamedTextColor.GRAY))
                        }
                        if (byTeam.size > 20) {
                            send(ctx.source.sender, Component.text("... +${byTeam.size - 20} more", NamedTextColor.DARK_GRAY))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        send(sender, Component.text("Reloading tournament roster for ONLINE players...", NamedTextColor.YELLOW))

                        TournamentManager.reloadOnlineRosterAsync { stats ->
                            if (stats.ok) {
                                send(
                                    sender,
                                    Component.text(
                                        "${stats.message}: scanned=${stats.scannedPlayers}, bypass=${stats.bypassPlayers}, participants=${stats.participants}, removed=${stats.notParticipants}",
                                        NamedTextColor.GREEN,
                                    )
                                )
                            } else {
                                send(sender, Component.text("Roster reload failed: ${stats.message}", NamedTextColor.RED))
                            }
                        }

                        Command.SINGLE_SUCCESS
                    }
            )
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

    private fun buildPlanNode(plugin: org.bukkit.plugin.java.JavaPlugin): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("plan")
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        val eventId = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
                        val stage = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_STAGE).trim()
                        val plan = TournamentMatchPlanManager.getApplicable(plugin, eventId, stage)

                        if (plan == null) {
                            val path = File(plugin.dataFolder, "minigamesapi/match_plan.yml").path
                            send(ctx.source.sender, Component.text("No match_plan.yml for event='$eventId' stage='$stage' ($path)", NamedTextColor.DARK_GRAY))
                            return@executes Command.SINGLE_SUCCESS
                        }

                        send(
                            ctx.source.sender,
                            Component.text(
                                "Match plan: event=${plan.eventId} stage=${plan.stage} matches=${plan.matches.size} generated_at=${plan.generatedAtMs} " +
                                    "| serial=${plan.serial} partial=${plan.allowPartialStart} minTeams=${plan.minTeamsToStart}",
                                NamedTextColor.GREEN,
                            )
                        )

                        for (m in plan.matches.take(20)) {
                            val teams = if (m.teams.isEmpty()) "<empty>" else m.teams.joinToString(",") { it ?: "~" }
                            val active = if (m.active) "active" else "inactive"
                            send(ctx.source.sender, Component.text("${m.matchId}: $active | teams=$teams", NamedTextColor.GRAY))
                        }
                        if (plan.matches.size > 20) {
                            send(ctx.source.sender, Component.text("... +${plan.matches.size - 20} more", NamedTextColor.DARK_GRAY))
                        }

                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        val loaded = TournamentMatchPlanManager.reload(plugin)
                        if (loaded == null) {
                            send(ctx.source.sender, Component.text("match_plan.yml is missing or invalid", NamedTextColor.RED))
                        } else {
                            send(ctx.source.sender, Component.text("Reloaded match_plan.yml for event=${loaded.eventId} stage=${loaded.stage}", NamedTextColor.GREEN))
                        }

                        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                            MatchmakingManager.rebuildTournamentWaitingAssignments()
                        }

                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("clear")
                    .executes { ctx ->
                        val ok = TournamentMatchPlanManager.clear(plugin)
                        send(
                            ctx.source.sender,
                            if (ok) Component.text("match_plan.yml deleted", NamedTextColor.YELLOW)
                            else Component.text("Failed to delete match_plan.yml", NamedTextColor.RED)
                        )

                        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                            MatchmakingManager.rebuildTournamentWaitingAssignments()
                        }

                        Command.SINGLE_SUCCESS
                    }
            )
    }

    private fun buildSeedNode(plugin: org.bukkit.plugin.java.JavaPlugin): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("seed")
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        val eventId = MiniGamesCore.configuration.get(ru.joutak.minigames.config.ConfigKeys.TOURNAMENT_EVENT_ID).trim()
                        val stage = MiniGamesCore.configuration.get(ru.joutak.minigames.config.ConfigKeys.TOURNAMENT_STAGE).trim()
                        val seeded = TournamentSeedingManager.getApplicable(plugin, eventId, stage)
                        if (seeded == null) {
                            send(ctx.source.sender, Component.text("No seeded_teams.yml for event='$eventId' stage='$stage'", NamedTextColor.DARK_GRAY))
                            return@executes Command.SINGLE_SUCCESS
                        }

                        send(
                            ctx.source.sender,
                            Component.text(
                                "Seeded teams: event=${seeded.eventId} from=${seeded.fromStage} to=${seeded.toStage} count=${seeded.teams.size} generated_at=${seeded.generatedAtMs}",
                                NamedTextColor.GREEN,
                            )
                        )
                        val preview = seeded.teams.take(30).joinToString(", ") { "#${it.seed}:${it.teamKey}" }
                        if (preview.isNotBlank()) {
                            send(ctx.source.sender, Component.text(preview, NamedTextColor.GRAY))
                        }
                        if (seeded.teams.size > 30) {
                            send(ctx.source.sender, Component.text("... +${seeded.teams.size - 30} more", NamedTextColor.DARK_GRAY))
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        val loaded = TournamentSeedingManager.load(plugin)
                        send(
                            ctx.source.sender,
                            if (loaded != null) Component.text("seeded_teams.yml reloaded (${loaded.teams.size} teams)", NamedTextColor.GREEN)
                            else Component.text("No seeded_teams.yml", NamedTextColor.DARK_GRAY)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("clear")
                    .executes { ctx ->
                        val ok = TournamentSeedingManager.clear(plugin)
                        send(
                            ctx.source.sender,
                            if (ok) Component.text("seeded_teams.yml cleared", NamedTextColor.GREEN)
                            else Component.text("Failed to clear seeded_teams.yml", NamedTextColor.RED)
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("generate")
                    .then(
                        Commands.literal("to")
                            .then(
                                Commands.argument("to_stage", StringArgumentType.word())
                                    .then(
                                        Commands.literal("top")
                                            .then(
                                                Commands.argument("take", IntegerArgumentType.integer(1, 512))
                                                    .executes { ctx ->
                                                        val toStage = StringArgumentType.getString(ctx, "to_stage")
                                                        val take = IntegerArgumentType.getInteger(ctx, "take")
                                                        val ok = generateSeededFromSnapshot(plugin, toStage, take, includeIncomplete = false)
                                                        send(
                                                            ctx.source.sender,
                                                            if (ok.ok) Component.text(ok.message, NamedTextColor.GREEN)
                                                            else Component.text(ok.message, NamedTextColor.RED)
                                                        )
                                                        Command.SINGLE_SUCCESS
                                                    }
                                                    .then(
                                                        Commands.literal("all")
                                                            .executes { ctx ->
                                                                val toStage = StringArgumentType.getString(ctx, "to_stage")
                                                                val take = IntegerArgumentType.getInteger(ctx, "take")
                                                                val ok = generateSeededFromSnapshot(plugin, toStage, take, includeIncomplete = true)
                                                                send(
                                                                    ctx.source.sender,
                                                                    if (ok.ok) Component.text(ok.message, NamedTextColor.GREEN)
                                                                    else Component.text(ok.message, NamedTextColor.RED)
                                                                )
                                                                Command.SINGLE_SUCCESS
                                                            }
                                                    )
                                            )
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("import")
                    .then(
                        Commands.literal("csv")
                            .then(
                                Commands.argument("file", StringArgumentType.word())
                                    .then(
                                        Commands.literal("to")
                                            .then(
                                                Commands.argument("to_stage", StringArgumentType.word())
                                                    .executes { ctx ->
                                                        val file = StringArgumentType.getString(ctx, "file")
                                                        val toStage = StringArgumentType.getString(ctx, "to_stage")
                                                        val res = importSeededFromCsv(plugin, file, toStage)
                                                        send(
                                                            ctx.source.sender,
                                                            if (res.ok) Component.text(res.message, NamedTextColor.GREEN)
                                                            else Component.text(res.message, NamedTextColor.RED)
                                                        )
                                                        Command.SINGLE_SUCCESS
                                                    }
                                            )
                                    )
                            )
                    )
            )
    }

    private data class SeedOpResult(val ok: Boolean, val message: String)

    private fun generateSeededFromSnapshot(plugin: org.bukkit.plugin.java.JavaPlugin, toStage: String, take: Int, includeIncomplete: Boolean): SeedOpResult {
        val cfg = TournamentQualifierManager.getConfig()
        val snapshot = TournamentQualifierManager.getSnapshot()
            ?: return SeedOpResult(false, "No qualifier snapshot. Run /itmocraft qualifier recalc")

        val rows = if (includeIncomplete) {
            snapshot.rows
        } else {
            snapshot.rows.filter { it.completedMatches >= cfg.minMatches }
        }

        if (rows.isEmpty()) {
            return SeedOpResult(false, "No teams to seed (includeIncomplete=$includeIncomplete, min_matches=${cfg.minMatches})")
        }

        val sorted = rows.sortedWith(compareByDescending<ru.joutak.minigames.tournament.qualifier.model.QualifierTeamRow> { it.eloRating }.thenBy { it.teamKey })
        val n = take.coerceAtMost(sorted.size)
        val chosen = sorted.take(n)

        val data = TournamentSeedingManager.SeededTeamsFile(
            eventId = cfg.eventId,
            fromStage = cfg.stage,
            toStage = toStage,
            generatedAtMs = System.currentTimeMillis(),
            teams = chosen.mapIndexed { idx, r ->
                TournamentSeedingManager.SeededTeam(
                    teamKey = r.teamKey,
                    seed = idx + 1,
                    rating = r.eloRating.toDouble(),
                    matchesCount = r.matchesCount,
                )
            }
        )

        val ok = TournamentSeedingManager.save(plugin, data)
        return if (ok) SeedOpResult(true, "Seeded ${data.teams.size} teams to stage '$toStage' (seeded_teams.yml)")
        else SeedOpResult(false, "Failed to save seeded_teams.yml")
    }

    private fun importSeededFromCsv(plugin: org.bukkit.plugin.java.JavaPlugin, relativePath: String, toStage: String): SeedOpResult {
        val f = resolveInApiFolder(plugin, relativePath)
            ?: return SeedOpResult(false, "Invalid path: $relativePath")

        if (!f.exists() || !f.isFile) {
            return SeedOpResult(false, "File not found: ${f.path}")
        }

        val lines = try {
            f.readLines(Charsets.UTF_8)
        } catch (_: Throwable) {
            return SeedOpResult(false, "Failed to read: ${f.path}")
        }

        val parsed = mutableListOf<Pair<String, Int?>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith('#')) continue

            val parts = line.split(',', ';', '	')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (parts.isEmpty()) continue
            val first = parts[0].lowercase()
            if (first == "team_key" || first == "team" || first == "teamkey") continue // header

            val teamKey = parts[0]
            val seed = parts.getOrNull(1)?.toIntOrNull()
            parsed.add(teamKey to seed)
        }

        if (parsed.isEmpty()) {
            return SeedOpResult(false, "No teams in CSV")
        }

        val seen = HashSet<String>()
        val unique = parsed.filter { seen.add(it.first) }

        var nextSeed = 1
        val teams = unique.map { (teamKey, seedOpt) ->
            val s = seedOpt ?: nextSeed++
            if (seedOpt != null && seedOpt >= nextSeed) nextSeed = seedOpt + 1
            TournamentSeedingManager.SeededTeam(teamKey = teamKey, seed = s)
        }.sortedBy { it.seed }

        val eventId = MiniGamesCore.configuration.get(ru.joutak.minigames.config.ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        val data = TournamentSeedingManager.SeededTeamsFile(
            eventId = eventId,
            fromStage = "import",
            toStage = toStage,
            generatedAtMs = System.currentTimeMillis(),
            teams = teams,
        )

        val ok = TournamentSeedingManager.save(plugin, data)
        return if (ok) SeedOpResult(true, "Imported ${teams.size} seeded teams to stage '$toStage' from ${f.name}")
        else SeedOpResult(false, "Failed to save seeded_teams.yml")
    }

    private fun resolveInApiFolder(plugin: org.bukkit.plugin.java.JavaPlugin, relativePath: String): File? {
        val raw = relativePath.trim().removePrefix("\"").removeSuffix("\"")
        if (raw.isBlank()) return null
        if (raw.startsWith('/') || raw.startsWith('\\')) return null
        if (raw.contains("..")) return null

        val apiDir = File(plugin.dataFolder, "minigamesapi")
        val f = File(apiDir, raw)
        val canonicalApi = try { apiDir.canonicalFile } catch (_: Throwable) { return null }
        val canonicalFile = try { f.canonicalFile } catch (_: Throwable) { return null }

        return if (canonicalFile.path.startsWith(canonicalApi.path)) canonicalFile else null
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
