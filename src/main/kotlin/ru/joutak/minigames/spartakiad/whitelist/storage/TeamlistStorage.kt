package ru.joutak.minigames.spartakiad.whitelist.storage

import ru.joutak.minigames.storage.Reloadable
import java.io.Closeable

interface TeamlistStorage : WhitelistStorage, Reloadable, Closeable {
    fun getTeams(): Map<String, List<String>>
}