package ru.joutak.minigames.spartakiad.whitelist.storage

import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.storage.Reloadable
import java.io.Closeable

interface WhitelistStorage : Reloadable, Closeable {
    fun getAll(): Set<String>

    fun contains(playerDto: PlayerDto): Boolean

    fun add(playerDto: PlayerDto): Boolean

    fun remove(playerDto: PlayerDto): Boolean
}
