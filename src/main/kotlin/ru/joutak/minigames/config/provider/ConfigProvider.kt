package ru.joutak.minigames.config.provider

import ru.joutak.minigames.config.ConfigKey

interface ConfigProvider : AutoCloseable {
    fun get(path: String): Any?

    fun set(
        path: String,
        value: Any?,
    )

    fun contains(path: String): Boolean

    fun save(values: Map<ConfigKey<*>, Any>)
}
