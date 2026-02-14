package ru.joutak.minigames.config

abstract class ConfigKey<T : Any>(
    val path: String,
    val default: T,
) {
    init {
        ConfigKeys.register(this)
    }

    @Throws(IllegalArgumentException::class)
    open fun validate(value: T) {
    }
}
