package ru.joutak.minigames.domain

import org.bukkit.entity.Player

/** Bound at runtime by VoiceChatIntegration when SVC is present. Null otherwise. */
interface VoiceSpectatorRegistry {
    fun allow(player: Player, instance: GameInstance)
    fun revoke(player: Player, instance: GameInstance)
}
