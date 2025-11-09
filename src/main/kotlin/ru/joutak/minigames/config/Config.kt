package ru.joutak.minigames.config

import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.storage.ConfigStorage
import java.util.concurrent.ConcurrentHashMap

class Config(
    private val configStorage: ConfigStorage,
) {
    private val values = ConcurrentHashMap<ConfigKey<*>, Any>()

    init {
        load()
    }

    private fun load() {
        for (key in ConfigKeys.getAll()) {
            val value =
                if (!configStorage.contains(key.path)) {
                    MiniGamesPlugin.instance.logger.warning(
                        "Не найден ключ ${key.path} в конфиге! Взято стандартное значение: ${key.default}",
                    )
                    configStorage.set(key.path, key.default)
                    key.default
                } else {
                    configStorage.get(key.path)!!
                }
            values[key] = value
        }
    }

    fun save() {
        configStorage.save(values)
    }

    fun close() {
        configStorage.close()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: ConfigKey<T>): T {
        val value = values[key] as? T
        if (value == null) {
            MiniGamesPlugin.instance.logger.warning(
                "Не удалось получить значение ключа $key из конфига! Взято стандартное значение: ${key.default}",
            )
            return key.default
        }
        return value
    }

    @Synchronized
    fun <T : Any> set(
        key: ConfigKey<T>,
        value: T,
    ) {
        key.validate(value)
        values[key] = value
        configStorage.set(key.path, value)
    }
}
