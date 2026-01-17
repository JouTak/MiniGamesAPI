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
        val worldName: String,
        val region: PedestalRegion,
        val seat: Location,
    )

    private lateinit var plugin: JavaPlugin
    private lateinit var configuration: Config

    @Volatile
    private var initialized = false

    @Volatile
    private var serverId: String = "server-1"

    @Volatile
    private var activeWorldName: String? = null

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
        activeWorldName = null
        initialized = false
    }

    fun isEnabled(): Boolean {
        if (!initialized) return false
        return configuration.get(ConfigKeys.CEREMONY_ENABLED) || configuration.get(ConfigKeys.TOURNAMENT_CEREMONY_ENABLED)
    }

    fun getActiveWorldName(): String? = activeWorldName

    fun getAssignment(uuid: UUID): PlayerAssignment? = assignments[uuid]

    fun clearAssignment(uuid: UUID) {
        assignments.remove(uuid)
    }

    fun isCeremonyWorld(world: World?): Boolean {
        val aw = activeWorldName ?: return false
        return world != null && world.name == aw
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
        val world = ensureCeremonyWorld() ?: return

        val pedestals = parsePedestals(world, configuration.get(ConfigKeys.CEREMONY_PEDESTALS))

        val onlineByTeam = HashMap<Int, MutableList<Player>>()
        for (pr in result.players) {
            val p = Bukkit.getPlayer(pr.playerUuid) ?: continue
            if (!p.isOnline) continue
            val teamId = pr.teamId
            val idx = if (teamId == null) ((p.uniqueId.hashCode() and Int.MAX_VALUE) % 4) else ((teamId % 4) + 4) % 4
            onlineByTeam.computeIfAbsent(idx) { ArrayList() }.add(p)
        }

        // Deterministic ordering for stable seat assignment.
        for (entry in onlineByTeam.entries) {
            entry.value.sortBy { it.uniqueId.toString() }
        }

        val fallbackLoc = safeSpawn(world)

        for ((teamIndex, players) in onlineByTeam.entries) {
            val region = pedestals.getOrNull(teamIndex)
            if (region == null) {
                for (p in players) {
                    assignments[p.uniqueId] = PlayerAssignment(world.name, PedestalRegion(fallbackLoc.blockX, fallbackLoc.blockX, fallbackLoc.blockZ, fallbackLoc.blockZ, fallbackLoc.blockY), fallbackLoc)
                    p.teleport(fallbackLoc)
                }
                continue
            }

            val seats = buildSeats(world, region)
            for ((i, p) in players.withIndex()) {
                val seat = seats.getOrNull(i) ?: seats.firstOrNull() ?: fallbackLoc
                assignments[p.uniqueId] = PlayerAssignment(world.name, region, seat)
                p.teleport(seat)
            }
        }

        // Players without a teamId in result.players but still online in match world won't be moved.
        // This is intentional: MiniGamesAPI cannot reliably know the full participant list without mode cooperation.
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

    private fun resolveCloneWorldName(templateWorld: String): String {
        val configured = configuration.get(ConfigKeys.CEREMONY_CLONE_WORLD).trim()
        if (configured.isNotBlank()) return configured

        // stable per-server clone name
        val sid = serverId.trim().ifBlank { "server" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tw = templateWorld.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "ceremony_${tw}_$sid"
    }

    private fun ensureCeremonyWorld(): World? {
        val template = resolveTemplateWorldName()
        if (template.isBlank()) return null

        val cloneName = resolveCloneWorldName(template)

        val existing = Bukkit.getWorld(cloneName)
        if (existing != null) {
            activeWorldName = existing.name
            return existing
        }

        // Fallback if Multiverse is missing.
        if (!isMultiverseAvailable()) {
            val w = Bukkit.getWorld(template)
            if (w != null) {
                activeWorldName = w.name
            }
            return w
        }

        // Best-effort world clone via Multiverse console commands.
        val console = Bukkit.getConsoleSender()

        // Ensure template is loaded if possible.
        Bukkit.dispatchCommand(console, "mv load $template")

        // Clone and load.
        Bukkit.dispatchCommand(console, "mv clone $template $cloneName")
        Bukkit.dispatchCommand(console, "mv load $cloneName")

        val created = Bukkit.getWorld(cloneName)
        if (created != null) {
            activeWorldName = created.name
            return created
        }

        // Last resort: use the template world itself.
        val fallback = Bukkit.getWorld(template)
        if (fallback != null) {
            activeWorldName = fallback.name
        }
        return fallback
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
                val _y2 = nums[4]
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
        // Prefer stable ordering; limit to 4 seats for classic 4-player teams.
        seats.sortWith(compareBy<Location> { it.blockX }.thenBy { it.blockZ })
        return if (seats.size > 4) seats.subList(0, 4) else seats
    }
}
