package ru.joutak.minigames.tournament.storage

import ru.joutak.minigames.tournament.model.TournamentTeamProgress
import java.util.UUID

/**
 * Storage for Tournament mode.
 *
 * This layer is intentionally small: the first iteration focuses on membership + stage progress.
 */
interface TournamentStorage {

    fun ensureSchema(autoCreate: Boolean)

    /**
     * Resolve team key for a player.
     * Implementations may bind UUID to a name-based roster on first successful lookup.
     */
    fun findTeamKey(eventId: String, playerUuid: UUID, playerName: String): String?

    fun getProgress(eventId: String, stage: String, teamKey: String): TournamentTeamProgress?

    fun getOrCreateProgress(eventId: String, stage: String, teamKey: String, defaultAttempts: Int): TournamentTeamProgress

    fun close() {}
}
