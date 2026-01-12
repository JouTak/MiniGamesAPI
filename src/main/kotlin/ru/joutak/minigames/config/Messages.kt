package ru.joutak.minigames.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesCore
import java.io.File

object Messages {

    private val amp: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()

    @Volatile
    private var yaml: YamlConfiguration = YamlConfiguration()

    @Volatile
    private var file: File? = null

    fun load(file: File) {
        this.file = file
        this.yaml = YamlConfiguration.loadConfiguration(file)
    }

    fun reload() {
        val f = file ?: return
        load(f)
    }

    fun has(path: String): Boolean = yaml.contains(path)

    fun getString(path: String): String? = yaml.getString(path)

    fun getStringList(path: String): List<String> = yaml.getStringList(path)

    fun prefixedComponent(path: String, placeholders: Map<String, String> = emptyMap()): Component {
        val msg = formatRaw(path, placeholders) ?: return Component.empty()
        val prefix = formatRaw("messages.prefix", placeholders) ?: ""
        return amp.deserialize(prefix + msg)
    }

    fun component(path: String, placeholders: Map<String, String> = emptyMap()): Component {
        val msg = formatRaw(path, placeholders) ?: return Component.empty()
        return amp.deserialize(msg)
    }

    fun prefixedLegacyString(path: String, placeholders: Map<String, String> = emptyMap()): String {
        val msg = formatRaw(path, placeholders) ?: ""
        val prefix = formatRaw("messages.prefix", placeholders) ?: ""
        return ChatColor.translateAlternateColorCodes('&', prefix + msg)
    }

    fun legacyString(path: String, placeholders: Map<String, String> = emptyMap()): String {
        val msg = formatRaw(path, placeholders) ?: ""
        return ChatColor.translateAlternateColorCodes('&', msg)
    }

    private fun formatRaw(path: String, placeholders: Map<String, String>): String? {
        val raw = yaml.getString(path) ?: return null
        val map = HashMap(placeholders)

        val modeDisplay = runCatching {
            MiniGamesCore.configuration.get(ConfigKeys.MODE_DISPLAY_NAME)
        }.getOrNull()
        if (!modeDisplay.isNullOrBlank()) {
            map.putIfAbsent("mode_display", modeDisplay)
        } else {
            val modeName = runCatching { MiniGamesCore.configuration.get(ConfigKeys.MODE_NAME) }.getOrNull()
            map.putIfAbsent("mode_display", modeName ?: "MINIGAME")
        }

        var out = raw
        map.forEach { (k, v) ->
            out = out.replace("{$k}", v)
        }
        return out
    }
}
