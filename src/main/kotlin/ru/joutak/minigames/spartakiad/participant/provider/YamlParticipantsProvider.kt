package ru.joutak.minigames.spartakiad.participant.provider

import org.bukkit.configuration.file.YamlConfiguration
import ru.joutak.minigames.MiniGamesPlugin
import java.io.File

class YamlParticipantsProvider(
    private val file: File,
) : ParticipantsProvider {
    private val key = "participants"

    private val yamlParticipants: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    @Synchronized
    override fun load(): List<String> {
        if (!yamlParticipants.contains(key)) {
            MiniGamesPlugin.instance.logger.warning("Не найден ключ $key в файле с участниками ${file.name}!")
            return emptyList()
        }
        return yamlParticipants.getStringList(key).map { it.trim() }.filter { it.isNotBlank() }
    }

    @Synchronized
    override fun save(participants: Collection<String>) {
        yamlParticipants.set(key, participants.sorted().toList())
        saveToFile(yamlParticipants)
    }

    private fun saveToFile(yaml: YamlConfiguration) {
        try {
            yaml.save(file)
        } catch (ex: Exception) {
            ex.printStackTrace() // или лог плагина
        }
    }
}
