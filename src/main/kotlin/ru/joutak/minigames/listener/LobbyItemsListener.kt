package ru.joutak.minigames.listener

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.command.ready.ReadyCommand
import ru.joutak.minigames.command.teamselect.TeamSelectCommand
import ru.joutak.minigames.lobby.LobbyItemsManager
import ru.joutak.minigames.managers.MatchmakingManager

object LobbyItemsListener : Listener {

    private fun ensureLater(player: Player, delayTicks: Long = 1L) {
        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (player.isOnline) {
                LobbyItemsManager.ensure(player)
            }
        }, delayTicks)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        ensureLater(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        ensureLater(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTeleport(event: PlayerTeleportEvent) {
        ensureLater(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        ensureLater(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        if (!LobbyItemsManager.isLobbyItem(item)) return

        event.isCancelled = true
        ensureLater(event.player, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        if (LobbyItemsManager.isLobbyItem(event.mainHandItem) || LobbyItemsManager.isLobbyItem(event.offHandItem)) {
            event.isCancelled = true
            ensureLater(event.player, 1L)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        val current = event.currentItem
        val cursor = event.cursor

        if (LobbyItemsManager.isLobbyItem(current) || LobbyItemsManager.isLobbyItem(cursor)) {
            event.isCancelled = true
            ensureLater(player, 1L)
            return
        }

        // Prevent hotbar swap (number key) from moving our item.
        if (event.hotbarButton >= 0) {
            val hotbarItem = player.inventory.getItem(event.hotbarButton)
            if (LobbyItemsManager.isLobbyItem(hotbarItem)) {
                event.isCancelled = true
                ensureLater(player, 1L)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return

        if (LobbyItemsManager.isLobbyItem(event.oldCursor) || LobbyItemsManager.isLobbyItem(event.cursor)) {
            event.isCancelled = true
            ensureLater(player, 1L)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        // Avoid duplicate firing for offhand
        if (event.hand != null && event.hand != EquipmentSlot.HAND) return

        // Handle only click actions; ignore PHYSICAL/other interactions.
        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK &&
            action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return

        val player = event.player

        // event.item can be null in some cases; take from main hand directly
        val item = player.inventory.itemInMainHand
        if (!LobbyItemsManager.isLobbyItem(item)) return

        // If somehow a lobby item appears during a running match — block it hard.
        if (MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            event.isCancelled = true
            LobbyItemsManager.remove(player)
            return
        }

        val id = LobbyItemsManager.getLobbyItemId(item) ?: return

        event.isCancelled = true

        when (val actionToRun = LobbyItemsManager.getAction(id)) {
            LobbyItemsManager.LobbyAction.Ready -> ReadyCommand.performReady(player)
            LobbyItemsManager.LobbyAction.TeamSelect -> TeamSelectCommand.openTeamSelect(player)
            is LobbyItemsManager.LobbyAction.Command -> {
                val ok = player.performCommand(actionToRun.command)
                if (!ok) {
                    player.sendMessage(actionToRun.denyMessage ?: Component.text("Команда недоступна.", NamedTextColor.RED))
                }
            }
            null -> {
                // Unknown id in config: ignore.
            }
        }
    }
}