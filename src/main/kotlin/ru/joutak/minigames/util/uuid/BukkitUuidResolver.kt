package ru.joutak.minigames.util.uuid

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesCore
import java.util.UUID

class BukkitUuidResolver : UuidResolver {
    override fun getUuid(name: String): UUID = Bukkit.getOfflinePlayer(name).uniqueId

    override fun getName(uuid: UUID): String? {
        val player = Bukkit.getOfflinePlayer(uuid)
        if (player.name == null) {
            MiniGamesCore.plugin.logger.severe("Не удалось получить ник участника с UUID $uuid!")
        }
        return player.name
    }
}
