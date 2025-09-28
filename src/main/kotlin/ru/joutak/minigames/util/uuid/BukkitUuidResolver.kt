package ru.joutak.minigames.util.uuid

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.domain.Participant
import java.util.UUID

class BukkitUuidResolver : UuidResolver {
    override fun resolveByName(name: String): UUID = Bukkit.getOfflinePlayer(name).uniqueId

    override fun getParticipant(uuid: UUID): Participant {
        val player = Bukkit.getOfflinePlayer(uuid)
        if (player.name.isNullOrBlank()) {
            MiniGamesPlugin.instance.logger.warning("Игрок с UUID $uuid не имеет ника!")
        }
        return Participant(player.uniqueId, if (player.name.isNullOrBlank()) "" else player.name!!)
    }
}
