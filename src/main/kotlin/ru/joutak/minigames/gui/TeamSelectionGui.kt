package ru.joutak.minigames.gui


import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameQueue
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.joutak.minigames.config.ConfigKeys

object TeamSelectionGui : Listener {

    private val openInventories = mutableMapOf<Player, TeamSelectionData>()

    
private val legacy = LegacyComponentSerializer.legacyAmpersand()

private data class TeamUi(
    val material: Material,
    val color: NamedTextColor,
    val customName: Component?,
)

@Suppress("UNCHECKED_CAST")
private fun teamUiFor(index: Int): TeamUi {
    val teamsRaw = MiniGamesCore.configuration.get(ConfigKeys.TEAMSELECT_TEAMS)
    val entry = teamsRaw[(index + 1).toString()] as? Map<*, *>

    val materialName = (entry?.get("material") as? String)?.trim()
    val colorName = (entry?.get("color") as? String)?.trim()
    val nameRaw = (entry?.get("name") as? String)?.trim()

    val material = if (!materialName.isNullOrEmpty()) {
        Material.matchMaterial(materialName, true)
    } else null

    val color = if (!colorName.isNullOrEmpty()) {
        NamedTextColor.NAMES.value(colorName.lowercase())
    } else null

    val customName = if (!nameRaw.isNullOrEmpty()) legacy.deserialize(nameRaw) else null

    return TeamUi(
        material = material ?: Material.WHITE_WOOL,
        color = color ?: NamedTextColor.WHITE,
        customName = customName,
    )
}

private fun titleComponent(): Component {
    val raw = MiniGamesCore.configuration.get(ConfigKeys.TEAMSELECT_TITLE)
    return legacy.deserialize(raw)
}



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
        val inventory = Bukkit.createInventory(null, size, titleComponent())

        instance.teams.forEachIndexed { index, team ->
            val ui = teamUiFor(index)
            val item = ItemStack(ui.material)

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

            meta.displayName((ui.customName ?: Component.text("Команда ${index + 1}", ui.color)).decorate(TextDecoration.BOLD))
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