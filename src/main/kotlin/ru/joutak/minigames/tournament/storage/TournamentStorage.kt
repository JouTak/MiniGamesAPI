package ru.joutak.minigames.tournament.storage

import ru.joutak.minigames.tournament.model.TournamentTeamProgress
import ru.joutak.minigames.tournament.model.TournamentTeamCaptain
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

    fun getTeamCaptain(eventId: String, teamKey: String): TournamentTeamCaptain?

    fun getProgress(eventId: String, stage: String, teamKey: String): TournamentTeamProgress?

    fun getOrCreateProgress(eventId: String, stage: String, teamKey: String, defaultAttempts: Int): TournamentTeamProgress

    /**
     * Decrease attempts for the given team in a stage.
     *
     * Returns updated progress (best effort). Implementations must clamp attempts_left to 0.
     */
    fun decrementAttempts(eventId: String, stage: String, teamKey: String, delta: Int = 1): TournamentTeamProgress?

    /**
     * Marks the team progress as won/lost for a stage.
     */
    fun setWon(eventId: String, stage: String, teamKey: String, won: Boolean)

    fun close() {}
}
