package ru.joutak.minigames.util.uuid

import java.util.*

interface UuidResolver {
    fun getUuid(name: String): UUID?

    fun getName(uuid: UUID): String?
}
