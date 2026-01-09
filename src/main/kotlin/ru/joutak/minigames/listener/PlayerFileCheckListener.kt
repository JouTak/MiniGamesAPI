package ru.joutak.minigames.listener

import org.bukkit.event.Listener

/**
 * Legacy listener that used to check a plain-text "teams.txt" before login.
 *
 * This feature was removed from MiniGamesAPI (it wasn't registered and caused config clutter).
 * The file is kept only to avoid breaking compilation for older branches.
 */
@Deprecated("Removed feature: player file whitelist")
object PlayerFileCheckListener : Listener