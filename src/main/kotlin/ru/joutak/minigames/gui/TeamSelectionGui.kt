package ru.joutak.minigames.gui


import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance

object TeamSelectionGui : Listener {

    private val openInventories = mutableMapOf<Player, TeamSelectionData>()

    init {
        Bukkit.getPluginManager().registerEvents(this, MiniGamesCore.plugin)
    }

    data class TeamSelectionData(
        val inventory: Inventory,
        val instance: GameInstance,
        val callback: (Player, Int) -> Unit
    )

    fun open(player: Player, instance: GameInstance, callback: (Player, Int) -> Unit) {
        val size = ((instance.teams.size - 1) / 9 + 1) * 9
        val inventory = Bukkit.createInventory(null, size, "Выбор команды")

        instance.teams.forEachIndexed { index, _ ->
            val item = ItemStack(Material.WHITE_WOOL)
            val meta = item.itemMeta
            meta?.setDisplayName("Команда ${index + 1}")
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
    fun onInventoryClose(event: InventoryCloseEvent) {
        openInventories.remove(event.player as? Player)
    }
}
