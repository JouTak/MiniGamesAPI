package ru.joutak.minigames.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataType
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.managers.MatchmakingManager

object LobbyItemsManager {

    // IMPORTANT: use stable namespace so it works even if API is shaded into another plugin.
    private val key: NamespacedKey = NamespacedKey("minigames", "lobby_item")
    private val legacy: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()

    sealed interface LobbyAction {
        data object Ready : LobbyAction
        data object TeamSelect : LobbyAction
        data class Command(
            val command: String,
            val denyMessage: Component? = null,
        ) : LobbyAction
    }

    data class LobbyItemDef(
        val id: String,
        val slot: Int,
        val material: Material,
        val name: Component,
        val lore: List<Component>,
        val action: LobbyAction,
        val enabled: Boolean,
    )

    @Volatile
    private var cacheEnabled: Boolean = true

    @Volatile
    private var cacheById: Map<String, LobbyItemDef> = emptyMap()

    @Volatile
    private var cacheBySlot: Map<Int, LobbyItemDef> = emptyMap()

    /**
     * Reload lobby items definitions from config.
     * Safe to call multiple times.
     */
    fun reloadFromConfig() {
        cacheEnabled = MiniGamesCore.configuration.get(ConfigKeys.LOBBY_ITEMS_ENABLED)

        val rawList = MiniGamesCore.configuration.get(ConfigKeys.LOBBY_HOTBAR_ITEMS)
        val defs = parseHotbar(rawList)

        cacheById = defs.associateBy { it.id }
        cacheBySlot = defs.associateBy { it.slot }
    }

    fun ensure(player: Player) {
        // Do not allow lobby items inside started games.
        if (MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            remove(player)
            return
        }

        if (cacheById.isEmpty()) {
            reloadFromConfig()
        }

        if (!cacheEnabled) {
            remove(player)
            return
        }

        // Replace old lobby items with the configured set.
        remove(player)

        val inv = player.inventory
        for ((slot, def) in cacheBySlot) {
            if (!def.enabled) continue
            if (slot !in 0..8) continue

            val item = createItem(def)
            placeFixed(inv, slot, item)
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

    fun isLobbyItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(key, PersistentDataType.STRING)
    }

    fun getLobbyItemId(item: ItemStack?): String? {
        if (!isLobbyItem(item)) return null
        val meta = item!!.itemMeta ?: return null
        return meta.persistentDataContainer.get(key, PersistentDataType.STRING)
    }

    fun getActionForId(id: String): LobbyAction? = cacheById[id]?.action

    private fun placeFixed(inv: PlayerInventory, slot: Int, item: ItemStack) {
        val existing = inv.getItem(slot)
        if (existing != null && existing.type != Material.AIR && !isLobbyItem(existing)) {
            val empty = inv.firstEmpty()
            if (empty != -1) {
                inv.setItem(empty, existing)
            } else {
                // No space: do not overwrite player's item.
                return
            }
        }
        inv.setItem(slot, item)
    }

    private fun createItem(def: LobbyItemDef): ItemStack {
        val item = ItemStack(def.material)
        val meta = item.itemMeta

        meta.displayName(def.name)
        if (def.lore.isNotEmpty()) meta.lore(def.lore)

        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)

        meta.persistentDataContainer.set(key, PersistentDataType.STRING, def.id)

        item.itemMeta = meta
        return item
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseHotbar(rawList: List<Map<String, Any>>): List<LobbyItemDef> {
        val defs = mutableListOf<LobbyItemDef>()

        for (entry in rawList) {
            val id = (entry["id"] as? String)?.trim()
            if (id.isNullOrEmpty()) continue

            val enabled = (entry["enabled"] as? Boolean) ?: true

            val slotNum = when (val s = entry["slot"]) {
                is Int -> s
                is Number -> s.toInt()
                is String -> s.toIntOrNull()
                else -> null
            } ?: continue

            val matName = (entry["material"] as? String)?.trim()
            val material = if (!matName.isNullOrEmpty()) {
                Material.matchMaterial(matName, true)
            } else null
            val safeMaterial = material ?: Material.BARRIER

            val nameRaw = (entry["name"] as? String) ?: id
            val name = legacy.deserialize(nameRaw)

            val loreRaw = entry["lore"]
            val loreStrings: List<String> =
                when (loreRaw) {
                    is List<*> -> loreRaw.mapNotNull { it as? String }
                    is String -> listOf(loreRaw)
                    else -> emptyList()
                }
            val lore = loreStrings.map { legacy.deserialize(it) }

            val actionMap = entry["action"] as? Map<*, *>
            val action = parseAction(actionMap) ?: continue

            defs += LobbyItemDef(
                id = id,
                slot = slotNum,
                material = safeMaterial,
                name = name,
                lore = lore,
                action = action,
                enabled = enabled,
            )
        }

        return defs
    }

    private fun parseAction(actionMap: Map<*, *>?): LobbyAction? {
        val type = (actionMap?.get("type") as? String)?.trim()?.uppercase() ?: return null

        return when (type) {
            "READY" -> LobbyAction.Ready
            "TEAM_SELECT" -> LobbyAction.TeamSelect
            "COMMAND" -> {
                val cmd = (actionMap["command"] as? String)?.trim()
                if (cmd.isNullOrEmpty()) return null

                val deny = (actionMap["deny_message"] as? String)?.trim()
                val denyComp = if (!deny.isNullOrEmpty()) legacy.deserialize(deny) else null
                LobbyAction.Command(cmd, denyComp)
            }
            else -> null
        }
    }
}