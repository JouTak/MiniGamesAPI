package ru.joutak.minigames.ceremony

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.results.ResultsConfig
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.TeamResult
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

object CeremonyManager {

    data class PedestalRegion(
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
        val baseY: Int,
    ) {
        fun containsBlock(x: Int, z: Int): Boolean {
            return x in minX..maxX && z in minZ..maxZ
        }
    }

    data class PlayerAssignment(
        val matchId: UUID,
        val worldName: String,
        val region: PedestalRegion,
        val seat: Location,
        val pedestalIndex: Int,
    )

    data class CeremonySession(
        val matchId: UUID,
        val worldName: String,
        val createdAtMs: Long,
    )

    private lateinit var plugin: JavaPlugin
    private lateinit var configuration: Config

    @Volatile
    private var initialized = false

    @Volatile
    private var serverId: String = "server-1"

    private val sessionsByWorld = ConcurrentHashMap<String, CeremonySession>()
    private val assignments = ConcurrentHashMap<UUID, PlayerAssignment>()

    fun initialize(plugin: JavaPlugin, configuration: Config, resultsFile: File) {
        this.plugin = plugin
        this.configuration = configuration

        serverId = try {
            ResultsConfig(resultsFile).serverId()
        } catch (_: Throwable) {
            "server-1"
        }

        initialized = true
    }

    fun shutdown() {
        assignments.clear()
        sessionsByWorld.clear()
        initialized = false
    }

    fun isEnabled(): Boolean {
        if (!initialized) return false
        return configuration.get(ConfigKeys.CEREMONY_ENABLED) || configuration.get(ConfigKeys.TOURNAMENT_CEREMONY_ENABLED)
    }

    fun getAssignment(uuid: UUID): PlayerAssignment? = assignments[uuid]

    fun clearAssignment(uuid: UUID) {
        assignments.remove(uuid)
    }

    fun isCeremonyWorld(world: World?): Boolean {
        val name = world?.name ?: return false
        return sessionsByWorld.containsKey(name)
    }

    fun resolveExitLocation(): Location? {
        val raw = configuration.get(ConfigKeys.CEREMONY_EXIT_WORLD).trim()
        val w = if (raw.isNotBlank()) {
            Bukkit.getWorld(raw)
        } else {
            Bukkit.getWorlds().firstOrNull { !isCeremonyWorld(it) }
        } ?: return null

        return safeSpawn(w)
    }

    fun handleMatchEnded(result: MatchResult) {
        if (!isEnabled()) return

        val delayTicks = configuration.get(ConfigKeys.CEREMONY_POST_MATCH_DELAY_TICKS).coerceAtLeast(0)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            try {
                moveMatchParticipantsToCeremony(result)
            } catch (t: Throwable) {
                plugin.logger.warning("Failed to move match participants to ceremony: ${t.message}")
                plugin.logger.fine(t.stackTraceToString())
            }
        }, delayTicks.toLong())
    }

    private fun moveMatchParticipantsToCeremony(result: MatchResult) {
        // Build podium mapping (placement 1..4 -> pedestal index 0..3)
        val podiumTeamIds = resolvePodiumTeamIds(result)
        if (podiumTeamIds.size < 4) return

        val pedestalIndexByTeamId = HashMap<Int, Int>(4)
        for (i in 0..3) {
            pedestalIndexByTeamId[podiumTeamIds[i]] = i
        }

        // Collect ONLINE participants only.
        val playersByPedestal = HashMap<Int, MutableList<Player>>()
        for (pr in result.players) {
            val teamId = pr.teamId ?: continue
            val pedestalIdx = pedestalIndexByTeamId[teamId] ?: continue

            val p = Bukkit.getPlayer(pr.playerUuid) ?: continue
            if (!p.isOnline) continue

            playersByPedestal.computeIfAbsent(pedestalIdx) { ArrayList() }.add(p)
        }

        // If nobody online -> do nothing and DO NOT clone ceremony world.
        if (playersByPedestal.isEmpty()) return

        // Deterministic ordering for stable seat assignment.
        for (entry in playersByPedestal.entries) {
            entry.value.sortBy { it.uniqueId.toString() }
        }

        val world = ensureCeremonyWorldForMatch(result.matchId) ?: return

        val pedestals = parsePedestals(world, configuration.get(ConfigKeys.CEREMONY_PEDESTALS))
        val fallbackLoc = safeSpawn(world)

        // Register session first so outsiders are gated immediately.
        sessionsByWorld[world.name] = CeremonySession(result.matchId, world.name, System.currentTimeMillis())

        for ((pedestalIndex, players) in playersByPedestal.entries) {
            val region = pedestals.getOrNull(pedestalIndex)
                ?: PedestalRegion(fallbackLoc.blockX, fallbackLoc.blockX, fallbackLoc.blockZ, fallbackLoc.blockZ, fallbackLoc.blockY)

            val seats = buildSeats(world, region)
            val seat0 = seats.firstOrNull() ?: fallbackLoc

            for ((i, p) in players.withIndex()) {
                val seat = seats.getOrNull(i) ?: seat0
                assignments[p.uniqueId] = PlayerAssignment(
                    matchId = result.matchId,
                    worldName = world.name,
                    region = region,
                    seat = seat,
                    pedestalIndex = pedestalIndex,
                )
                p.teleport(seat)
            }
        }
    }

    private fun resolvePodiumTeamIds(result: MatchResult): IntArray {
        // Prefer explicit placements.
        val teams = result.teams
        if (teams.isEmpty()) {
            // Fallback: use teamIds from players.
            val ids = result.players.mapNotNull { it.teamId }.distinct().take(4)
            if (ids.size < 4) return IntArray(0)
            return ids.sorted().toIntArray()
        }

        val byPlace = HashMap<Int, Int>()
        for (t in teams) {
            val p = t.placement
            if (p != null && p in 1..4 && !byPlace.containsKey(p)) {
                byPlace[p] = t.teamId
            }
        }

        if (!byPlace.containsKey(1)) {
            val winner = teams.firstOrNull { it.isWinner }?.teamId
            if (winner != null) byPlace[1] = winner
        }

        val used = HashSet<Int>(byPlace.values)
        val remainingTeams = teams
            .filter { it.teamId !in used }
            .sortedWith(compareByDescending<TeamResult> { it.score ?: Double.NEGATIVE_INFINITY }.thenBy { it.teamId })

        val remainingPlaces = (1..4).filter { !byPlace.containsKey(it) }
        val itTeams = remainingTeams.iterator()
        for (pl in remainingPlaces) {
            if (!itTeams.hasNext()) break
            byPlace[pl] = itTeams.next().teamId
        }

        if (byPlace.size < 4) {
            // Last-resort: stable teamId ordering
            val ids = teams.map { it.teamId }.distinct().take(4)
            if (ids.size < 4) return IntArray(0)
            return ids.sorted().toIntArray()
        }

        return intArrayOf(byPlace[1]!!, byPlace[2]!!, byPlace[3]!!, byPlace[4]!!)
    }

    private fun safeSpawn(world: World): Location {
        val s = world.spawnLocation
        return Location(world, s.x, s.y, s.z, s.yaw, s.pitch)
    }

    private fun resolveTemplateWorldName(): String {
        val raw = configuration.get(ConfigKeys.CEREMONY_TEMPLATE_WORLD).trim()
        if (raw.isNotBlank()) return raw
        return configuration.get(ConfigKeys.TOURNAMENT_CEREMONY_WORLD).trim().ifBlank { "tourney_ceremony" }
    }

    private fun ensureCeremonyWorldForMatch(matchId: UUID): World? {
        val template = resolveTemplateWorldName()
        if (template.isBlank()) return null

        val cloneName = resolveCloneWorldName(template, matchId)

        val existing = Bukkit.getWorld(cloneName)
        if (existing != null) return existing

        // Do not fallback to a shared template world: ceremony must be per-match.
        if (!isMultiverseAvailable()) {
            plugin.logger.warning("Ceremony requires Multiverse-Core for per-match clone, but it's missing/enabled=false")
            return null
        }

        val console = Bukkit.getConsoleSender()
        Bukkit.dispatchCommand(console, "mv load $template")
        Bukkit.dispatchCommand(console, "mv clone $template $cloneName")
        Bukkit.dispatchCommand(console, "mv load $cloneName")

        val created = Bukkit.getWorld(cloneName)
        if (created != null) return created

        plugin.logger.warning("Failed to create ceremony clone world '$cloneName' from template '$template'")
        return null
    }

    private fun resolveCloneWorldName(templateWorld: String, matchId: UUID): String {
        val tw = sanitizeWorldPart(templateWorld)
        val sid = sanitizeWorldPart(serverId)
        val match = matchId.toString().replace("-", "").take(8)

        val raw = configuration.get(ConfigKeys.CEREMONY_CLONE_WORLD).trim()
        val pattern = if (raw.isBlank()) "ceremony_${tw}_{server}_{match}" else raw

        var name = sanitizeWorldPart(
            pattern
                .replace("{template}", tw)
                .replace("{server}", sid)
                .replace("{match}", match)
        )

        // If user provided a fixed name without {match}, keep it unique.
        if (!raw.contains("{match}") && !name.endsWith(match)) {
            name = "${name}_$match"
        }

        // Hard limit to avoid filesystem / MV issues.
        if (name.length > 32) {
            val suffix = "_$match"
            val maxPrefix = 32 - suffix.length
            name = if (maxPrefix <= 0) {
                "cer_$match"
            } else {
                name.take(maxPrefix).trimEnd('_') + suffix
            }
        }

        return name
    }

    private fun sanitizeWorldPart(raw: String): String {
        return raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "world" }
    }

    private fun isMultiverseAvailable(): Boolean {
        val p = Bukkit.getPluginManager().getPlugin("Multiverse-Core")
        return p != null && p.isEnabled
    }

    private fun parsePedestals(world: World, raw: List<String>): List<PedestalRegion> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<PedestalRegion>()
        for (s in raw) {
            val r = parseRegion(world, s) ?: continue
            out.add(r)
        }
        return out
    }

    private fun parseRegion(world: World, raw: String?): PedestalRegion? {
        val r = raw?.trim().orEmpty()
        if (r.isEmpty()) return null

        val parts = r.replace(',', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size < 2) return null

        val spawnY = world.spawnLocation.blockY

        return when (nums.size) {
            2 -> {
                val x1 = nums[0]
                val z1 = nums[1]
                PedestalRegion(x1, x1 + 1, z1, z1 + 1, spawnY)
            }
            3 -> {
                val x1 = nums[0]
                val y = nums[1]
                val z1 = nums[2]
                PedestalRegion(x1, x1 + 1, z1, z1 + 1, y)
            }
            4 -> {
                val x1 = nums[0]
                val z1 = nums[1]
                val x2 = nums[2]
                val z2 = nums[3]
                PedestalRegion(min(x1, x2), max(x1, x2), min(z1, z2), max(z1, z2), spawnY)
            }
            5 -> {
                val x1 = nums[0]
                val y = nums[1]
                val z1 = nums[2]
                val x2 = nums[3]
                val z2 = nums[4]
                PedestalRegion(min(x1, x2), max(x1, x2), min(z1, z2), max(z1, z2), y)
            }
            else -> {
                val x1 = nums[0]
                val y = nums[1]
                val z1 = nums[2]
                val x2 = nums[3]
                val z2 = nums[5]
                PedestalRegion(min(x1, x2), max(x1, x2), min(z1, z2), max(z1, z2), y)
            }
        }
    }

    private fun buildSeats(world: World, region: PedestalRegion): List<Location> {
        val seats = ArrayList<Location>()
        for (x in region.minX..region.maxX) {
            for (z in region.minZ..region.maxZ) {
                seats.add(Location(world, x + 0.5, region.baseY + 1.0, z + 0.5, 0f, 0f))
            }
        }
        // Stable ordering; for current rules we never need more than 4 seats.
        seats.sortWith(compareBy<Location> { it.blockX }.thenBy { it.blockZ })
        return if (seats.size > 4) seats.subList(0, 4) else seats
    }
}
