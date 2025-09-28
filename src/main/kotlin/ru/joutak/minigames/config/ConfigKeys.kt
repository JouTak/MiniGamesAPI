package ru.joutak.minigames.config

object ConfigKeys {
    private val configKeys = mutableSetOf<ConfigKey<*>>()

    val ENABLED = object : ConfigKey<Boolean>("enabled", false) {}

    fun register(key: ConfigKey<*>) {
        configKeys += key
    }

    fun getKeys(): Set<ConfigKey<*>> = configKeys
}
