package ru.joutak.minigames.domain

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import org.bukkit.Material

/**
 * Public, immutable description of a team's visual identity (material, color, display name).
 *
 * The single source of truth is the MiniGamesAPI `config.yml`
 * (`teamselect.teams.<n>.{material,color,name}`). Modes should read styles from
 * [ru.joutak.minigames.MiniGamesAPI.getTeamStyle] / [getTeamStyles] instead of
 * defining their own per-mode hardcoded names/colors.
 *
 * `teamNumber` is 1-based, matching the keys used in the config.
 */
data class TeamStyle(
    val teamNumber: Int,
    val material: Material,
    val color: NamedTextColor,
    val displayName: Component,
    val displayNameLegacy: String,
    val displayNamePlain: String,
) {
    /** Legacy Bukkit ChatColor equivalent of [color], for modes still on legacy text APIs. */
    val chatColor: ChatColor by lazy {
        val key = NamedTextColor.NAMES.key(color) ?: "white"
        runCatching { ChatColor.valueOf(key.uppercase()) }.getOrDefault(ChatColor.WHITE)
    }
}
