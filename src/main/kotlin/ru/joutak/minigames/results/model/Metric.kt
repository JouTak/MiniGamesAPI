package ru.joutak.minigames.results.model

/**
 * A flexible metric stored as key -> value.
 *
 * Only one of [valueInt], [valueReal], [valueText] should normally be set.
 */
data class Metric(
    val key: String,
    val valueInt: Long? = null,
    val valueReal: Double? = null,
    val valueText: String? = null,
) {
    init {
        require(key.isNotBlank()) { "Metric.key must not be blank" }
    }

    companion object {
        fun int(key: String, value: Long) = Metric(key = key, valueInt = value)
        fun real(key: String, value: Double) = Metric(key = key, valueReal = value)
        fun text(key: String, value: String) = Metric(key = key, valueText = value)
    }
}
