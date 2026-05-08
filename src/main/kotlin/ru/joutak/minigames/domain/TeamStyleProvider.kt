package ru.joutak.minigames.domain

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesCore

object TeamStyleProvider {

    private val amp = LegacyComponentSerializer.legacyAmpersand()

    fun get(teamNumber: Int): TeamStyle {
        val yaml = runCatching { YamlConfiguration.loadConfiguration(MiniGamesCore.apiConfigFile) }
            .getOrDefault(YamlConfiguration())

        val base = "teamselect.teams.$teamNumber"

        val material = yaml.getString("$base.material")
            ?.trim()
            ?.uppercase()
            ?.let { Material.matchMaterial(it) }
            ?: defaultMaterial(teamNumber)

        val color = parseNamedColor(yaml.getString("$base.color")?.trim()?.uppercase())
            ?: defaultColor(teamNumber)

        val rawName = yaml.getString("$base.name")

        val display: Component
        val legacy: String
        if (!rawName.isNullOrBlank()) {
            display = amp.deserialize(rawName)
            legacy = rawName
        } else {
            display = Component.text("Команда $teamNumber", color)
            legacy = amp.serialize(display)
        }

        @Suppress("DEPRECATION")
        val translated = ChatColor.translateAlternateColorCodes('&', legacy)
        @Suppress("DEPRECATION")
        val plainText = ChatColor.stripColor(translated) ?: translated

        return TeamStyle(
            teamNumber = teamNumber,
            material = material,
            color = color,
            displayName = display,
            displayNameLegacy = legacy,
            displayNamePlain = plainText,
        )
    }

    fun getAll(count: Int): List<TeamStyle> = (1..count).map { get(it) }

    private fun parseNamedColor(name: String?): NamedTextColor? {
        if (name.isNullOrBlank()) return null
        return NamedTextColor.NAMES.value(name.lowercase())
    }

    private fun defaultMaterial(teamNumber: Int): Material = when (teamNumber) {
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

    private fun defaultColor(teamNumber: Int): NamedTextColor = when (teamNumber) {
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
