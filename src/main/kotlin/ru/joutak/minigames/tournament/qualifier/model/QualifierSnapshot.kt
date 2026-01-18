package ru.joutak.minigames.tournament.qualifier.model

import java.util.UUID

/** In-memory snapshot of qualifier standings. */
data class QualifierSnapshot(
    val eventId: String,
    val stage: String,
    val generatedAtMs: Long,
    val consideredUntilMs: Long?,
    val matchesConsidered: Int,
    val matchesSkipped: Int,
    val rows: List<QualifierTeamRow>,
)

data class QualifierTeamRow(
    val teamKey: String,
    val matchesCount: Int,
    val eloRating: Int,
    val avgPlace: Double,
    val avgPaint: Double,
    val bestPaint: Double,
    val lastMatchAtMs: Long,
)

/**
 * Audit entry for a processed match in qualifier recalculation.
 *
 * For determinism, place ordering is stable: paint desc, team_key asc.
 */
data class QualifierMatchAudit(
    val matchId: UUID,
    val endedAtMs: Long,
    val skipped: Boolean = false,
    val skippedReason: String? = null,
    val teams: List<QualifierMatchTeamAudit> = emptyList(),
)

data class QualifierMatchTeamAudit(
    val teamKey: String,
    val place: Int,
    val paintPercent: Double,
    val ratingBefore: Double,
    val delta: Double,
    val ratingAfter: Double,
)
