package ru.joutak.minigames.domain

import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.configuration.serialization.SerializableAs
import java.util.UUID

@SerializableAs("PlayerData")
data class PlayerData(
    val uuid: UUID,
    val name: String,
    val attempts: Int,
    val won: Boolean = false,
) : ConfigurationSerializable {
    companion object {
        @JvmStatic
        fun deserialize(serialized: Map<String, Any>): PlayerData {
            val uuid = UUID.fromString(serialized["uuid"] as String)
            val name = serialized["name"] as String
            val attempts = serialized["attempts"] as Int
            val hasWon = serialized["won"] as Boolean
            return PlayerData(uuid, name, attempts, hasWon)
        }
    }

    override fun serialize(): Map<String, Any> {
        val serializedParticipant = mutableMapOf<String, Any>()
        serializedParticipant["uuid"] = uuid.toString()
        serializedParticipant["name"] = name
        serializedParticipant["attempts"] = attempts
        serializedParticipant["won"] = won
        return serializedParticipant
    }
}
