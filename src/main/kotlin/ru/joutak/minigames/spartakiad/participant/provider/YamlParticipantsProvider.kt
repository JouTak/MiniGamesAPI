package ru.joutak.minigames.spartakiad.participant.provider

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class YamlParticipantsProvider(
    private val participantsFile: File,
) : ParticipantsProvider {
    private val participantsRef = AtomicReference<YamlConfiguration>(YamlConfiguration())

    @Volatile
    private var pendingSaveFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yaml-participants-writer-${participantsFile.name}").apply { isDaemon = true }
        }

    private val participantsFileWatcher: ParticipantsFileWatcher = ParticipantsFileWatcher(participantsFile, this)

    @Volatile
    private var lastSavedAt: Long = System.currentTimeMillis()

    private val key = "participants"

    override fun getLastSavedAt(): Long = lastSavedAt

    override fun getAll(): List<String> {
        val yamlParticipants = participantsRef.get()
        if (!yamlParticipants.contains(key)) {
            MiniGamesPlugin.instance.logger.warning("Не найден ключ $key в файле с участниками ${participantsFile.name}!")
            return emptyList()
        }
        return yamlParticipants.getStringList(key).map { it.trim() }.filter { it.isNotBlank() }
    }

    override fun contains(name: String): Boolean {
        val yamlParticipants = participantsRef.get()
        if (!yamlParticipants.contains(key)) {
            return false
        }
        return yamlParticipants.getStringList(key).contains(name)
    }

    override fun add(name: String): Boolean {
        val yamlParticipants = participantsRef.get()
        val participantsList = yamlParticipants.getStringList(key)
        if (participantsList.contains(name)) {
            return false
        }
        participantsList.add(name)
        yamlParticipants.set(key, participantsList)
        scheduleSave(yamlParticipants)
        return true
    }

    override fun remove(name: String): Boolean {
        val yamlParticipants = participantsRef.get()
        val participantsList = yamlParticipants.getStringList(key)
        if (participantsList.contains(name)) {
            return false
        }
        participantsList.remove(name)
        yamlParticipants.set(key, participantsList)
        scheduleSave(yamlParticipants)
        return true
    }

    @Synchronized
    override fun save(participants: Collection<String>) {
        executor.execute {
            val yamlParticipants = participantsRef.get()
            yamlParticipants.set(key, participants.sorted().toList())
            scheduleSave(yamlParticipants)
        }
    }

    private fun scheduleSave(yaml: YamlConfiguration) {
        pendingSaveFuture?.cancel(false)
        pendingSaveFuture =
            executor.schedule({
                saveToFile(yaml)
            }, MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS), TimeUnit.MILLISECONDS)
        lastSavedAt = System.currentTimeMillis()
    }

    private fun saveToFile(yaml: YamlConfiguration) {
        try {
            yaml.save(participantsFile)
            lastSavedAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить список участников: ${t.message}")
            MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
        }
    }

    override fun reload(): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync {
            try {
                val yamlParticipants = YamlConfiguration.loadConfiguration(participantsFile)
                participantsRef.set(yamlParticipants)
            } catch (t: Throwable) {
                MiniGamesPlugin.instance.logger.severe("Не удалось загрузить файл со списком участников: ${t.message}")
                participantsRef.set(YamlConfiguration())
            }
        }

    override fun close() {
        participantsFileWatcher.close()

        val future =
            executor.submit {
                val yamlParticipants = participantsRef.get()
                pendingSaveFuture?.cancel(false)
                saveToFile(yamlParticipants)
            }
        try {
            future.get(MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_CLOSE_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.severe("Не удалось сохранить список участников: ${t.message}")
            MiniGamesPlugin.instance.logger.severe(t.stackTraceToString())
        } finally {
            executor.shutdown()
        }
    }
}
