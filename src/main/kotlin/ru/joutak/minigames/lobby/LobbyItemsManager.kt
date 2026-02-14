package ru.joutak.minigames.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataType
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.ui.QueueBossBarManager

object LobbyItemsManager {

    const val QUICK_READY_ID: String = "quick_ready"
    const val TEAM_SELECT_ID: String = "team_select"
    const val LOBBY_RETURN_ID: String = "lobby_return"

    // Stable namespace so it works even if API is shaded into another plugin.
    private val key: NamespacedKey = NamespacedKey("minigames", "lobby_item")
    private val amp: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()

    sealed interface LobbyAction {
        data object Ready : LobbyAction
        data object TeamSelect : LobbyAction
        data class Command(val command: String, val denyMessage: Component?) : LobbyAction
    }

    private data class LobbyHotbarItem(
        val id: String,
        val enabled: Boolean,
        val slot: Int,
        val item: ItemStack,
        val action: LobbyAction,
    )

    @Volatile
    private var lobbyItemsEnabled: Boolean = true

    @Volatile
    private var hotbarItems: List<LobbyHotbarItem> = defaultItems()

    private val actionsById: MutableMap<String, LobbyAction> = mutableMapOf()

    fun reloadFromConfig() {
        val cfgFile = MiniGamesCore.apiConfigFile
        if (!cfgFile.exists()) {
            lobbyItemsEnabled = true
            hotbarItems = defaultItems()
            rebuildActionIndex()
            return
        }

        try {
            val yaml = YamlConfiguration.loadConfiguration(cfgFile)
            lobbyItemsEnabled = yaml.getBoolean("lobby.items.enabled", true)

            val parsed = parseHotbarList(yaml)
            hotbarItems = if (parsed.isNotEmpty()) parsed else defaultItems()
        } catch (t: Throwable) {
            MiniGamesCore.plugin.logger.severe("Failed to load lobby items from config: ${t.message}")
            lobbyItemsEnabled = true
            hotbarItems = defaultItems()
        }

        rebuildActionIndex()
    }

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

    fun getAction(id: String): LobbyAction? = actionsById[id]

    fun ensure(player: Player) {
        // Spectators should not get lobby items/UI (used by admin /spectate in minigames).
        if (player.gameMode == GameMode.SPECTATOR) {
            remove(player)
            QueueBossBarManager.ensure(player)
            return
        }

        if (!lobbyItemsEnabled) {
            remove(player)
            QueueBossBarManager.ensure(player)
            return
        }

        if (MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            remove(player)
            QueueBossBarManager.ensure(player)
            return
        }

        // Always keep lobby items consistent (also fixes "moved/changed" items).
        remove(player)
        apply(player)

        // BossBar should be visible to everyone in lobby.
        QueueBossBarManager.ensure(player)
    }

    fun apply(player: Player) {
        if (!lobbyItemsEnabled) return

        val inv = player.inventory

        val tournament = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)

        for (def in hotbarItems) {
            if (!def.enabled) continue
            if (def.slot !in 0..8) continue

            // In tournament mode, players should not use manual ready / team selection.
            if (tournament && (def.action == LobbyAction.Ready || def.action == LobbyAction.TeamSelect)) continue

            placeFixed(inv, def.slot, def.item.clone())
        }
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

    private fun placeFixed(inv: PlayerInventory, slot: Int, item: ItemStack) {
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

    private fun parseHotbarList(yaml: YamlConfiguration): List<LobbyHotbarItem> {
        val list = yaml.getMapList("lobby.items.hotbar")
        if (list.isEmpty()) return emptyList()

        val result = mutableListOf<LobbyHotbarItem>()
        for (raw in list) {
            val id = (raw["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val enabled = (raw["enabled"] as? Boolean) ?: true
            val slot = parseInt(raw["slot"]) ?: continue
            val materialName = (raw["material"] as? String)?.trim()?.uppercase()
            val material = materialName?.let { Material.matchMaterial(it) } ?: continue

            val msgBase = "ui.lobby.items.$id"

            val nameStr = Messages.getString("$msgBase.name") ?: (raw["name"] as? String)
            val name = nameStr?.let { amp.deserialize(it) } ?: Component.text(id, NamedTextColor.WHITE)

            val loreStrings = when {
                Messages.has("$msgBase.lore") -> Messages.getStringList("$msgBase.lore")
                raw["lore"] is List<*> -> (raw["lore"] as List<*>).mapNotNull { it as? String }
                else -> emptyList()
            }
            val lore = loreStrings.map { amp.deserialize(it) }

            var action = parseAction(raw["action"]) ?: continue

            if (action is LobbyAction.Command && action.denyMessage == null) {
                val denyStr = Messages.getString("$msgBase.deny_message")
                if (!denyStr.isNullOrBlank()) {
                    action = LobbyAction.Command(action.command, amp.deserialize(denyStr))
                }
            }

            val item = createItem(material, id, name, lore)

            result += LobbyHotbarItem(id, enabled, slot, item, action)
        }
        return result
    }

    private fun parseAction(raw: Any?): LobbyAction? {
        val map = raw as? Map<*, *> ?: return null
        val type = (map["type"] as? String)?.trim()?.uppercase() ?: return null
        return when (type) {
            "READY" -> LobbyAction.Ready
            "TEAM_SELECT", "TEAMSELECT" -> LobbyAction.TeamSelect
            "COMMAND" -> {
                val command = (map["command"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val deny = (map["deny_message"] as? String)?.let { amp.deserialize(it) }
                LobbyAction.Command(command, deny)
            }
            else -> null
        }
    }

    private fun createItem(material: Material, id: String, name: Component, lore: List<Component>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, id)
        item.itemMeta = meta
        return item
    }

    private fun rebuildActionIndex() {
        actionsById.clear()
        for (def in hotbarItems) {
            actionsById[def.id] = def.action
        }
    }

    private fun defaultItems(): List<LobbyHotbarItem> {
        val ready = createItem(
            Material.EMERALD,
            QUICK_READY_ID,
            Component.text("Готов", NamedTextColor.GREEN),
            listOf(Component.text("Быстро встать в очередь", NamedTextColor.GRAY))
        )

        val teamSelect = createItem(
            Material.NETHER_STAR,
            TEAM_SELECT_ID,
            Component.text("Выбор команды", NamedTextColor.AQUA),
            listOf(Component.text("Открыть меню выбора команды", NamedTextColor.GRAY))
        )

        val lobbyReturn = createItem(
            Material.COMPASS,
            LOBBY_RETURN_ID,
            Component.text("В лобби", NamedTextColor.YELLOW),
            listOf(Component.text("Вернуться в лобби", NamedTextColor.GRAY))
        )

        return listOf(
            LobbyHotbarItem(QUICK_READY_ID, true, 2, ready, LobbyAction.Ready),
            LobbyHotbarItem(TEAM_SELECT_ID, true, 4, teamSelect, LobbyAction.TeamSelect),
            LobbyHotbarItem(LOBBY_RETURN_ID, true, 6, lobbyReturn, LobbyAction.Command("lobby", null))
        )
    }

    private fun parseInt(v: Any?): Int? {
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
}
