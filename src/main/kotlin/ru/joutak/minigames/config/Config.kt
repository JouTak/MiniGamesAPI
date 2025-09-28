package ru.joutak.minigames.config

import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.provider.ConfigProvider
import java.util.concurrent.ConcurrentHashMap

class Config(
    private val provider: ConfigProvider,
) {
    private val values = ConcurrentHashMap<ConfigKey<*>, Any>()

    init {
        load()
    }

    private fun load() {
        for (key in ConfigKeys.getKeys()) {
            val value =
                if (!provider.contains(key.path)) {
                    MiniGamesPlugin.instance.logger.warning(
                        "Не найден ключ ${key.path} в конфиге! Взято стандартное значение: ${key.default}",
                    )
                    provider.set(key.path, key.default)
                    key.default
                } else {
                    provider.get(key.path) ?: key.default
                }
            values[key] = value
        }
    }

    fun save() {
        provider.save(values)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: ConfigKey<T>): T = values[key] as? T ?: key.default

    @Synchronized
    fun <T : Any> set(
        key: ConfigKey<T>,
        value: T,
    ) {
        key.validate(value)
        values[key] = value
        provider.set(key.path, value)
    }
}
