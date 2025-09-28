package ru.joutak.minigames.util.uuid

import org.bukkit.World
import org.bukkit.entity.Player
import ru.joutak.minigames.domain.Participant
import xyz.kyngs.librelogin.api.LibreLoginPlugin
import java.util.UUID

class LibreLoginUuidResolver(
    private val libreLogin: LibreLoginPlugin<Player, World>,
) : UuidResolver {
    override fun resolveByName(name: String): UUID = libreLogin.databaseProvider.getByName(name).uuid

    override fun getParticipant(uuid: UUID): Participant {
        val user = libreLogin.databaseProvider.getByUUID(uuid)
        return Participant(user.uuid, user.lastNickname)
    }
}
