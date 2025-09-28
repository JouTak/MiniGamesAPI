package ru.joutak.minigames.spartakiad.participant.provider

interface ParticipantsProvider {
    fun load(): List<String>

    fun save(participants: Collection<String>)
}
