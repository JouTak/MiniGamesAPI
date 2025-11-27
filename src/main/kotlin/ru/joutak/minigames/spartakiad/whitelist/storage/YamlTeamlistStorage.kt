package ru.joutak.minigames.spartakiad.whitelist.storage

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.event.WhitelistChangeEvent
import ru.joutak.minigames.storage.FileStorageWatcher
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class YamlTeamlistStorage(private val teamlistFile: File) : TeamlistStorage {
    private val teamlistRef = AtomicReference<YamlConfiguration>(YamlConfiguration())

    @Volatile
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yaml-teamlist-writer-${teamlistFile.name}").apply { isDaemon = true }
        }

    private val whitelistFileWatcher: FileStorageWatcher = FileStorageWatcher(teamlistFile, this) {
        Bukkit.getScheduler().runTask(
            MiniGamesCore.plugin,
            Runnable {
                Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                    Bukkit.getPluginManager().callEvent(WhitelistChangeEvent())
                })
            },
        )
    }

    @Volatile
    private var lastSavedAt: Long = System.currentTimeMillis()

    private val yamlKey = "teams"

    override fun getLastSavedAt(): Long = lastSavedAt

    override fun getTeams(): Map<String, List<String>> {
        val teamlistYaml = teamlistRef.get()
        if (!teamlistYaml.contains(yamlKey)) {
            MiniGamesCore.plugin.logger.warning("Не найден ключ $yamlKey в файле с командами-участницами ${teamlistFile.name}!")
            return emptyMap()
        }
        val teamsSection = teamlistYaml.getConfigurationSection(yamlKey)!!
        val teamsMap = HashMap<String, List<String>>()

        for (key in teamsSection.getKeys(false)) {
            teamsMap[key] = teamsSection.getStringList(key)
        }
        return teamsMap
    }

    override fun getAll(): Set<String> {
        val whitelistYaml = teamlistRef.get()
        if (!whitelistYaml.contains(yamlKey)) {
            MiniGamesCore.plugin.logger.warning("Не найден ключ $yamlKey в файле с командами-участницами ${teamlistFile.name}!")
            return emptySet()
        }
        val teamsMap = getTeams()
        return teamsMap.values.flatten().toSet()
    }

    override fun contains(playerDto: PlayerDto): Boolean {
        return getAll().contains(playerDto.name)
    }

    override fun add(playerDto: PlayerDto): Boolean {
        if (playerDto.teamName == null || contains(playerDto)) {
            return false
        }

        val teamlistYaml = teamlistRef.get()
        val teamsSection = teamlistYaml.getConfigurationSection(yamlKey)!!
        if (!teamsSection.getKeys(false).contains(playerDto.teamName)) {
            teamsSection.set(playerDto.teamName, listOf(playerDto.name))
            scheduleSave(teamlistYaml)
            return true
        }

        if (teamsSection.getStringList(playerDto.teamName).contains(playerDto.name)) {
            return false
        }

        val teamMembers = teamsSection.getStringList(playerDto.teamName)
        teamMembers.add(playerDto.name)
        teamsSection.set(playerDto.teamName, teamMembers)
        scheduleSave(teamlistYaml)
        return true
    }

    override fun remove(playerDto: PlayerDto): Boolean {
        if (playerDto.teamName == null || !contains(playerDto)) {
            return false
        }

        val whitelistYaml = teamlistRef.get()
        val teamsSection = whitelistYaml.getConfigurationSection(yamlKey)!!
        val members = teamsSection.getStringList(playerDto.teamName)
        if (!members.contains(playerDto.name)) return false
        members.remove(playerDto.name)
        teamsSection.set(playerDto.teamName, members)
        scheduleSave(whitelistYaml)

        return true
    }

    private fun scheduleSave(teamlistYaml: YamlConfiguration) {
        pendingSaveFuture?.cancel(false)
        pendingSaveFuture =
            executor.schedule({
                saveToFile(teamlistYaml)
            }, MiniGamesCore.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS), TimeUnit.MILLISECONDS)
        lastSavedAt = System.currentTimeMillis()
    }

    private fun saveToFile(teamlistYaml: YamlConfiguration) {
        try {
            teamlistYaml.save(teamlistFile)
            lastSavedAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            MiniGamesCore.plugin.logger.severe("Не удалось сохранить список команд-участниц: ${t.message}")
            MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
        }
    }

    override fun reload(): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync {
            try {
                val yamlParticipants = YamlConfiguration.loadConfiguration(teamlistFile)
                teamlistRef.set(yamlParticipants)
            } catch (t: Throwable) {
                MiniGamesCore.plugin.logger.severe("Не удалось загрузить файл со списком команд-участниц: ${t.message}")
                teamlistRef.set(YamlConfiguration())
            }
        }

    override fun close() {
        whitelistFileWatcher.close()

        val future =
            executor.submit {
                if (pendingSaveFuture != null) {
                    val yamlParticipants = teamlistRef.get()
                    pendingSaveFuture?.cancel(false)
                    saveToFile(yamlParticipants)
                }
            }
        try {
            future.get(
                MiniGamesCore.configuration.get(ConfigKeys.STORAGE_CLOSE_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS
            )
        } catch (t: Throwable) {
            MiniGamesCore.plugin.logger.severe("Не удалось сохранить список команд-участниц: ${t.message}")
            MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
        } finally {
            executor.shutdown()
        }
    }
}