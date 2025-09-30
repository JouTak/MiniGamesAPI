package ru.joutak.minigames.util.uuid

import org.bukkit.World
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesPlugin
import xyz.kyngs.librelogin.api.LibreLoginPlugin
import java.util.UUID

class LibreLoginUuidResolver(
    private val libreLogin: LibreLoginPlugin<Player, World>,
) : UuidResolver {
    override fun getUuid(name: String): UUID? {
        val uuid = libreLogin.databaseProvider.getByName(name)?.uuid
        if (uuid == null) {
            MiniGamesPlugin.instance.logger.severe("Не удалось получить UUID участника $name!")
        }
        return uuid
    }

    override fun getName(uuid: UUID): String? {
        val user = libreLogin.databaseProvider.getByUUID(uuid)
        if (user == null) {
            MiniGamesPlugin.instance.logger.severe("Не удалось получить данные об участнике с UUID $uuid!")
        }
        return user?.lastNickname
    }
}
