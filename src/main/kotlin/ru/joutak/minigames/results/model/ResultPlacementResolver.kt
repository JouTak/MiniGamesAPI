package ru.joutak.minigames.results.model

/** Builds competition placements: finishers first, leavers second, score descending inside each group. */
object ResultPlacementResolver {
    data class Entry<K>(
        val key: K,
        val score: Double,
        val completionStatus: CompletionStatus = CompletionStatus.FINISHED,
    )

    fun <K> resolve(entries: Collection<Entry<K>>): Map<K, Int> {
        val sorted = entries.sortedWith(
            compareBy<Entry<K>> { if (it.completionStatus == CompletionStatus.FINISHED) 0 else 1 }
                .thenByDescending { it.score }
        )

        val placements = LinkedHashMap<K, Int>(sorted.size)
        var previous: Entry<K>? = null
        var previousPlace = 0
        for ((index, entry) in sorted.withIndex()) {
            val prev = previous
            val samePlace = prev != null &&
                prev.completionStatus == entry.completionStatus &&
                prev.score.compareTo(entry.score) == 0
            val place = if (samePlace) previousPlace else index + 1
            placements[entry.key] = place
            previous = entry
            previousPlace = place
        }
        return placements
    }
}
