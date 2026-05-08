package ru.joutak.minigames.domain

import org.bukkit.entity.Player

/**
 * Cross-package indirection so [ru.joutak.minigames.MiniGamesAPI] can offer a
 * voice-spectator API without referencing classes from the
 * `integration/voicechat/` package (which transitively imports
 * `de.maxhenkel.*` — would fail to load on servers without SimpleVoiceChat).
 *
 * Implementation lives in `integration/voicechat/` and is bound at runtime by
 * [ru.joutak.minigames.integration.voicechat.VoiceChatIntegration]. On servers
 * without SVC the registry stays null and the corresponding API methods become
 * no-ops.
 */
interface VoiceSpectatorRegistry {
    /**
     * Mark [player] as allowed to enter ANY of [instance]'s per-team voice
     * groups. Idempotent. Has no effect if the instance has no voice groups
     * (e.g. SVC integration is disabled or the match never started).
     */
    fun allow(player: Player, instance: GameInstance)

    /** Inverse of [allow]. Idempotent. */
    fun revoke(player: Player, instance: GameInstance)
}
