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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameQueue // <-- Необходимый импорт

object TeamSelectionGui : Listener {

    private val openInventories = mutableMapOf<Player, TeamSelectionData>()

    // Карта для цветной шерсти, соответствующая порядку команд 0, 1, 2, 3
    private val TEAM_MATERIALS: Map<Int, Material> = mapOf(
        0 to Material.RED_WOOL,    // 1-я команда (Красная)
        1 to Material.YELLOW_WOOL,   // 2-я команда (Желтая)
        2 to Material.GREEN_WOOL, // 3-я команда (Зеленая)
        3 to Material.BLUE_WOOL   // 4-я команда (Синяя)
    )

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
        val inventory = Bukkit.createInventory(null, size, Component.text("Выбор команды", NamedTextColor.DARK_GRAY))

        instance.teams.forEachIndexed { index, team ->
            val material = TEAM_MATERIALS[index] ?: Material.WHITE_WOOL
            val item = ItemStack(material)

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

            meta.displayName(Component.text("Команда ${index + 1}", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
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
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        openInventories.remove(player)
        GameQueue.removePlayer(player)
    }
}