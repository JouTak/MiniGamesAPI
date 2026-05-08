package ru.joutak.minigames.domain

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import org.bukkit.Material

/** Visual identity of a team (1-based [teamNumber]). Loaded from `teamselect.teams.<n>` in config.yml. */
data class TeamStyle(
    val teamNumber: Int,
    val material: Material,
    val color: NamedTextColor,
    val displayName: Component,
    val displayNameLegacy: String,
    val displayNamePlain: String,
) {
    val chatColor: ChatColor by lazy {
        val key = NamedTextColor.NAMES.key(color) ?: "white"
        runCatching { ChatColor.valueOf(key.uppercase()) }.getOrDefault(ChatColor.WHITE)
    }
}
