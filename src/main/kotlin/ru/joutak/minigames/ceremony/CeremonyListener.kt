package ru.joutak.minigames.ceremony

import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent

object CeremonyListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(e: PlayerMoveEvent) {
        val p = e.player
        val a = CeremonyManager.getAssignment(p.uniqueId) ?: return
        val w = p.world
        if (w.name != a.worldName) return

        val to = e.to ?: return
        val bx = to.blockX
        val bz = to.blockZ
        if (a.region.containsBlock(bx, bz)) return

        val from = e.from
        // Allow looking around, block only the movement to another block.
        val back = Location(from.world, from.x, from.y, from.z, to.yaw, to.pitch)
        e.to = back
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) {
        val p = e.player
        val a = CeremonyManager.getAssignment(p.uniqueId) ?: return

        val fromWorld = e.from.world
        val toWorld = e.to?.world

        // Leaving ceremony world -> clear restriction.
        if (fromWorld != null && fromWorld.name == a.worldName && toWorld != null && toWorld.name != a.worldName) {
            CeremonyManager.clearAssignment(p.uniqueId)
            return
        }

        // Teleporting within ceremony world outside of pedestal -> deny.
        if (toWorld != null && toWorld.name == a.worldName) {
            val to = e.to ?: return
            val bx = to.blockX
            val bz = to.blockZ
            if (!a.region.containsBlock(bx, bz)) {
                e.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(e: PlayerRespawnEvent) {
        val p = e.player
        val a = CeremonyManager.getAssignment(p.uniqueId) ?: return
        val w = e.respawnLocation.world
        if (w != null && w.name == a.worldName) {
            e.respawnLocation = a.seat
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        CeremonyManager.clearAssignment(e.player.uniqueId)
    }
}
