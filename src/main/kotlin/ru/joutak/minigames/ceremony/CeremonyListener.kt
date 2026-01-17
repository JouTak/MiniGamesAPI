package ru.joutak.minigames.ceremony

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.joutak.minigames.MiniGamesCore

object CeremonyListener : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPlayerQuit(e: PlayerQuitEvent) {
        CeremonyManager.clearAssignment(e.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerChangedWorld(e: PlayerChangedWorldEvent) {
        val player = e.player
        if (!CeremonyManager.isCeremonyWorld(player.world)) return

        val a = CeremonyManager.getAssignment(player.uniqueId)
        if (a == null || a.worldName != player.world.name) {
            ejectFromCeremony(player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerTeleport(e: PlayerTeleportEvent) {
        val toWorld = e.to?.world ?: return
        if (!CeremonyManager.isCeremonyWorld(toWorld)) return

        val player = e.player
        val a = CeremonyManager.getAssignment(player.uniqueId)
        if (a == null || a.worldName != toWorld.name) {
            val exit = CeremonyManager.resolveExitLocation()
            if (exit == null) {
                e.isCancelled = true
            } else {
                e.to = copy(exit, e.to)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(e: PlayerMoveEvent) {
        val player = e.player
        if (!CeremonyManager.isCeremonyWorld(player.world)) return

        val a = CeremonyManager.getAssignment(player.uniqueId)
        if (a == null || a.worldName != player.world.name) {
            // Player is inside ceremony clone but not allowed.
            ejectFromCeremony(player)
            return
        }

        val from = e.from
        val to = e.to ?: return

        // Allow head movement / jumping within the same block.
        if (from.blockX == to.blockX && from.blockZ == to.blockZ) return

        val region = a.region
        val inside = to.blockX in region.minX..region.maxX && to.blockZ in region.minZ..region.maxZ
        if (inside) return

        // Push back without warnings.
        e.to = Location(from.world, from.x, from.y, from.z, to.yaw, to.pitch)
    }

    private fun ejectFromCeremony(player: org.bukkit.entity.Player) {
        val exit = CeremonyManager.resolveExitLocation() ?: return
        Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
            if (player.isOnline && CeremonyManager.isCeremonyWorld(player.world)) {
                player.teleport(exit)
            }
        })
    }

    private fun copy(base: Location, yawPitchSource: Location?): Location {
        val src = yawPitchSource ?: base
        return Location(base.world, base.x, base.y, base.z, src.yaw, src.pitch)
    }
}
