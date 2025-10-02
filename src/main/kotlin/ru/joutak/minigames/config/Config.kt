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
                    provider.get(key.path)!!
                }
            values[key] = value
        }
    }

    fun save() {
        provider.save(values)
    }

    fun saveAndClose() {
        save()
        provider.close()
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
        provider.set(key.path, value)
    }
}
