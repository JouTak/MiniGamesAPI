package ru.joutak.minigames.config.storage

import ru.joutak.minigames.config.ConfigKey
import java.io.Closeable
import java.util.concurrent.CompletableFuture

interface ConfigStorage : Closeable {
    fun get(path: String): Any?

    fun set(
        path: String,
        value: Any?,
    )

    fun contains(path: String): Boolean

    fun save(values: Map<ConfigKey<*>, Any>)

    fun reload(): CompletableFuture<Unit>
}
