package ru.joutak.minigames.results.model

/**
 * Optional context for cross-mode events like Spartakiad rounds.
 */
data class MatchContext(
    val eventId: String,
    val stage: String,
    val groupKey: String? = null,
    val ruleSet: String? = null,
)
