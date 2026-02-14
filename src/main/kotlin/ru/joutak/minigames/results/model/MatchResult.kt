package ru.joutak.minigames.results.model

import java.util.UUID

/**
 * Match result to be stored in a shared database.
 *
 * [modeKey] will be overridden by MiniGamesAPI according to config (mode.name).
 */
data class MatchResult(
    val matchId: UUID = UUID.randomUUID(),
    val modeKey: String = "",
    val mapKey: String? = null,
    val serverId: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val context: MatchContext? = null,
    val teams: List<TeamResult> = emptyList(),
    val players: List<PlayerResult> = emptyList(),
) {
    val durationMs: Long
        get() = (endedAtMs - startedAtMs).coerceAtLeast(0)
}
