package ru.joutak.minigames.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime
import java.util.*

// ===== SERIALIZERS =====

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString())
    }
}

// ===== MAIN CLASS =====

@Serializable
data class GameResult(
    @Serializable(with = UUIDSerializer::class)
    val gameUuid: UUID,

    val gameName: String,

    val participants: List<Player>,
    val winners: List<Player>,

    @Serializable(with = LocalDateTimeSerializer::class)
    val dateTime: LocalDateTime = LocalDateTime.now(),

    val results: Map<@Serializable(with = UUIDSerializer::class) UUID, Int>
)
