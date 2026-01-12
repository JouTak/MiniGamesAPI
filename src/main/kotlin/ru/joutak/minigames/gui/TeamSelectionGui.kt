package ru.joutak.minigames.gui


import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameQueue
import org.bukkit.configuration.file.YamlConfiguration

object TeamSelectionGui : Listener {

    private val openInventories = mutableMapOf<Player, TeamSelectionData>()

    private val amp: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()

    private data class TeamStyle(
        val material: Material,
        val color: NamedTextColor,
        val displayName: Component?,
    )

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
            val style = getTeamStyle(yaml, teamNumber)

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

            val teamName = style.displayName
                ?: Component.text("Команда $teamNumber", style.color).decorate(TextDecoration.BOLD)
            meta.displayName(teamName)
            meta.lore(loreList)

            item.itemMeta = meta
            inventory.setItem(index, item)
        }

        openInventories[player] = TeamSelectionData(inventory, instance, callback)
        player.openInventory(inventory)
    }

    private fun getTeamStyle(yaml: YamlConfiguration, teamNumber: Int): TeamStyle {
        val base = "teamselect.teams.$teamNumber"

        val materialName = yaml.getString("$base.material")?.trim()?.uppercase()
        val material = materialName?.let { Material.matchMaterial(it) } ?: defaultTeamMaterial(teamNumber)

        val colorName = yaml.getString("$base.color")?.trim()?.uppercase()
        val color = parseNamedColor(colorName) ?: defaultTeamColor(teamNumber)

        val nameStr = Messages.getString("ui.teamselect.teams.$teamNumber.name") ?: yaml.getString("$base.name")
        val display = nameStr?.let { amp.deserialize(it) }

        return TeamStyle(material, color, display)
    }

    private fun parseNamedColor(name: String?): NamedTextColor? {
        if (name.isNullOrBlank()) return null
        return NamedTextColor.NAMES.value(name.lowercase())
    }

    private fun defaultTeamMaterial(teamNumber: Int): Material {
        return when (teamNumber) {
            1 -> Material.RED_WOOL
            2 -> Material.YELLOW_WOOL
            3 -> Material.GREEN_WOOL
            4 -> Material.BLUE_WOOL
            5 -> Material.CYAN_WOOL
            6 -> Material.PURPLE_WOOL
            7 -> Material.ORANGE_WOOL
            8 -> Material.LIGHT_BLUE_WOOL
            9 -> Material.WHITE_WOOL
            10 -> Material.BLACK_WOOL
            11 -> Material.MAGENTA_WOOL
            12 -> Material.LIME_WOOL
            13 -> Material.PINK_WOOL
            14 -> Material.BROWN_WOOL
            15 -> Material.GRAY_WOOL
            16 -> Material.LIGHT_GRAY_WOOL
            else -> Material.WHITE_WOOL
        }
    }

    private fun defaultTeamColor(teamNumber: Int): NamedTextColor {
        return when (teamNumber) {
            1 -> NamedTextColor.RED
            2 -> NamedTextColor.YELLOW
            3 -> NamedTextColor.GREEN
            4 -> NamedTextColor.BLUE
            5 -> NamedTextColor.AQUA
            6 -> NamedTextColor.LIGHT_PURPLE
            7 -> NamedTextColor.GOLD
            8 -> NamedTextColor.DARK_AQUA
            9 -> NamedTextColor.WHITE
            10 -> NamedTextColor.BLACK
            11 -> NamedTextColor.DARK_PURPLE
            12 -> NamedTextColor.DARK_GREEN
            13 -> NamedTextColor.LIGHT_PURPLE
            14 -> NamedTextColor.DARK_RED
            15 -> NamedTextColor.GRAY
            16 -> NamedTextColor.DARK_GRAY
            else -> NamedTextColor.WHITE
        }
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