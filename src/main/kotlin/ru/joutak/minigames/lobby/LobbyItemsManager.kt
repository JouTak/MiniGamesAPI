package ru.joutak.minigames.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.joutak.minigames.managers.MatchmakingManager

object LobbyItemsManager {

    const val QUICK_READY_ID: String = "quick_ready"
    const val TEAM_SELECT_ID: String = "team_select"
    const val LOBBY_RETURN_ID: String = "lobby_return"

    // 0-based hotbar indices. User asked "not in 1" -> avoid slot 0.
    private const val QUICK_READY_SLOT: Int = 2
    private const val TEAM_SELECT_SLOT: Int = 4
    private const val LOBBY_RETURN_SLOT: Int = 6

    // IMPORTANT: use stable namespace so it works even if API is shaded into another plugin.
    private val key: NamespacedKey = NamespacedKey("minigames", "lobby_item")

    fun isLobbyItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(key, PersistentDataType.STRING)
    }

    fun getLobbyItemId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(key, PersistentDataType.STRING)
    }

    fun ensure(player: Player) {
        if (MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            remove(player)
            return
        }

        // Always keep lobby items consistent (also fixes "moved/changed" items).
        remove(player)
        apply(player)
    }

    fun apply(player: Player) {
        val inv = player.inventory

        placeFixed(inv, QUICK_READY_SLOT, createQuickReadyItem())
        placeFixed(inv, TEAM_SELECT_SLOT, createTeamSelectItem())
        placeFixed(inv, LOBBY_RETURN_SLOT, createLobbyReturnItem())
    }

    fun remove(player: Player) {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val item = inv.getItem(i)
            if (isLobbyItem(item)) {
                inv.clear(i)
            }
        }
    }

    private fun placeFixed(inv: org.bukkit.inventory.PlayerInventory, slot: Int, item: ItemStack) {
        val existing = inv.getItem(slot)
        if (existing != null && existing.type != Material.AIR && !isLobbyItem(existing)) {
            val empty = inv.firstEmpty()
            if (empty != -1) {
                inv.setItem(empty, existing)
            } else {
                // No free slot: keep player's item and skip placing ours to avoid deleting items.
                return
            }
        }
        inv.setItem(slot, item)
    }

    private fun createQuickReadyItem(): ItemStack {
        val item = ItemStack(Material.EMERALD)
        val meta = item.itemMeta
        meta.displayName(Component.text("Готов", NamedTextColor.GREEN))
        meta.lore(listOf(Component.text("Быстро встать в очередь", NamedTextColor.GRAY)))
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, QUICK_READY_ID)
        item.itemMeta = meta
        return item
    }

    private fun createTeamSelectItem(): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        val meta = item.itemMeta
        meta.displayName(Component.text("Выбор команды", NamedTextColor.AQUA))
        meta.lore(listOf(Component.text("Открыть меню выбора команды", NamedTextColor.GRAY)))
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, TEAM_SELECT_ID)
        item.itemMeta = meta
        return item
    }

    private fun createLobbyReturnItem(): ItemStack {
        val item = ItemStack(Material.COMPASS)
        val meta = item.itemMeta
        meta.displayName(Component.text("В лобби", NamedTextColor.YELLOW))
        meta.lore(listOf(Component.text("Вернуться в лобби", NamedTextColor.GRAY)))
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, LOBBY_RETURN_ID)
        item.itemMeta = meta
        return item
    }
}
