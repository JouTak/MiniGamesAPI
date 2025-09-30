package ru.joutak.minigames.util.uuid

import java.util.UUID

interface UuidResolver {
    fun getUuid(name: String): UUID?

    fun getName(uuid: UUID): String?
}
