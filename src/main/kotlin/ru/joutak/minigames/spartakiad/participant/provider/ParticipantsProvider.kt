package ru.joutak.minigames.spartakiad.participant.provider

interface ParticipantsProvider : AutoCloseable {
    fun load(): List<String>

    fun save(participants: Collection<String>)
}
