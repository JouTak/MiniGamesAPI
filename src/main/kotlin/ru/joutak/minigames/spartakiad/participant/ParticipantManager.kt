package ru.joutak.minigames.spartakiad.participant

import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.Participant
import ru.joutak.minigames.spartakiad.participant.storage.ParticipantStorage
import ru.joutak.minigames.util.uuid.UuidResolver
import java.util.*
import java.util.concurrent.CompletableFuture

class ParticipantManager(
    private val participantStorage: ParticipantStorage,
    private val uuidResolver: UuidResolver,
) {
    fun get(uuid: UUID): CompletableFuture<Participant?> =
        participantStorage.getParticipant(
            uuid,
        )

    fun get(name: String): CompletableFuture<Participant?> =
        getUuid(name).thenCompose { uuid ->
            participantStorage.getParticipant(uuid)
        }

    private fun getUuid(name: String): CompletableFuture<UUID> =
        CompletableFuture
            .supplyAsync {
                uuidResolver.getUuid(name) ?: throw NullPointerException("Не удалось получить UUID игрока $name!")
            }

    fun createIfNotExists(name: String): CompletableFuture<Participant> =
        getUuid(name).thenCompose { uuid ->
            participantStorage.createIfNotExists(
                uuid,
                name,
                MiniGamesPlugin.instance.configuration.get(ConfigKeys.SPARTAKIAD_ATTEMPTS),
            )
        }

    fun decrementAttempt(uuid: UUID): CompletableFuture<Int?> = participantStorage.decrementAttempt(uuid)

    fun updateName(
        uuid: UUID,
        newName: String,
    ): CompletableFuture<Unit> =
        participantStorage.getParticipant(uuid).thenCompose { participant ->
            if (participant == null) {
                CompletableFuture.failedFuture(
                    NullPointerException("Не удалось получить данные об игроке с UUID $uuid для обновления ника!"),
                )
            } else {
                if (participant.name == newName) {
                    CompletableFuture.completedFuture(null)
                } else {
                    val updated =
                        Participant(participant.uuid, newName, participant.attempts, participant.won)
                    participantStorage.updateParticipant(updated)
                }
            }
        }

    fun markPlayerWon(uuid: UUID): CompletableFuture<Unit> = participantStorage.markWon(uuid)

    fun hasPlayerWon(uuid: UUID): CompletableFuture<Boolean> = participantStorage.hasWon(uuid)

    fun close() {
        participantStorage.close()
    }
}
