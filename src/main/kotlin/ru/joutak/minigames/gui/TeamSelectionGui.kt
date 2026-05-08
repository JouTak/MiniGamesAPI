package ru.joutak.minigames.gui


import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameQueue
import org.bukkit.configuration.file.YamlConfiguration

object TeamSelectionGui : Listener {

    private val openInventories = mutableMapOf<Player, TeamSelectionData>()

    data class TeamSelectionData(
        val inventory: Inventory,
        val instance: GameInstance,
        val callback: (Player, Int) -> Unit
    )

    fun open(player: Player, instance: GameInstance, callback: (Player, Int) -> Unit) {
        val size = ((instance.teams.size - 1) / 9 + 1) * 9
        val yaml = YamlConfiguration.loadConfiguration(MiniGamesCore.apiConfigFile)
        val title = Messages.getString("ui.teamselect.title") ?: yaml.getString("teamselect.title") ?: "&8Выбор команды"
        val invTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', title)
        val inventory = Bukkit.createInventory(null, size, invTitle)


        instance.teams.forEachIndexed { index, team ->
            val teamNumber = index + 1
            val style = MiniGamesAPI.getTeamStyle(teamNumber)

            val item = ItemStack(style.material)

            val meta = item.itemMeta

            val currentPlayers = team.size
            val maxPlayers = instance.config.playersPerTeam

            val playerNames = team.map { Component.text("- ${it.name}", NamedTextColor.GRAY) }

            val loreList = mutableListOf<Component>()

            val color = if (currentPlayers < maxPlayers) NamedTextColor.GREEN else NamedTextColor.RED
            loreList.add(Component.text("Игроков: $currentPlayers/$maxPlayers", color))

            loreList.add(Component.empty())

            if (currentPlayers > 0) {
                loreList.add(Component.text("Состав команды:", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                loreList.addAll(playerNames)
            } else {
                loreList.add(Component.text("Команда пуста", NamedTextColor.GRAY))
            }

            meta.displayName(style.displayName)
            meta.lore(loreList)

            item.itemMeta = meta
            inventory.setItem(index, item)
        }

        openInventories[player] = TeamSelectionData(inventory, instance, callback)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val data = openInventories[player] ?: return

        if (event.clickedInventory != data.inventory) {
            event.isCancelled = true
            return
        }

        event.isCancelled = true
        val slot = event.slot
        if (slot >= data.instance.teams.size) return

        data.callback(player, slot)
        player.closeInventory()
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val data = openInventories[player] ?: return

        // Cancel only if drag touches the top inventory (GUI), otherwise let player drag in their inventory.
        if (event.view.topInventory != data.inventory) return

        val touchesTop = event.rawSlots.any { it < data.inventory.size }
        if (touchesTop) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        openInventories.remove(player)
        GameQueue.removePlayer(player)
    }
}
