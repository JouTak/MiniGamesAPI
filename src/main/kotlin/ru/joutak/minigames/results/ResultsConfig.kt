package ru.joutak.minigames.results

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ResultsConfig(
    private val file: File,
) {
    @Volatile
    private var yaml: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    init {
        ensureDefaultsAndSave()
    }

    fun reload() {
        yaml = YamlConfiguration.loadConfiguration(file)
        ensureDefaultsAndSave()
    }

    private fun ensureDefaultsAndSave() {
        var changed = false

        fun ensure(path: String, value: Any) {
            if (!yaml.contains(path)) {
                yaml.set(path, value)
                changed = true
            }
        }

        ensure("results.enabled", false)
        ensure("results.server_id", "server-1")
        ensure("results.schema.auto_create", true)
        ensure("results.jdbc.url", "")
        ensure("results.jdbc.username", "")
        ensure("results.jdbc.password", "")
        ensure("results.jdbc.driver", "")
        ensure("results.jdbc.connect_timeout_seconds", 5)

        if (changed) {
            try {
                file.parentFile?.mkdirs()
                yaml.save(file)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    fun enabled(): Boolean = yaml.getBoolean("results.enabled", false)

    fun serverId(): String = yaml.getString("results.server_id", "server-1") ?: "server-1"

    fun schemaAutoCreate(): Boolean = yaml.getBoolean("results.schema.auto_create", true)

    fun jdbcUrl(): String = yaml.getString("results.jdbc.url", "") ?: ""

    fun jdbcUsername(): String = yaml.getString("results.jdbc.username", "") ?: ""

    fun jdbcPassword(): String = yaml.getString("results.jdbc.password", "") ?: ""

    fun jdbcDriver(): String = yaml.getString("results.jdbc.driver", "") ?: ""

    fun connectTimeoutSeconds(): Int = (yaml.getInt("results.jdbc.connect_timeout_seconds", 5)).coerceAtLeast(1)
}
