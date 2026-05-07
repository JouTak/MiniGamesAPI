package ru.joutak.minigames.integration.voicechat

import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatServerApi
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Creates one voice group per team on match start and dissolves them on match end.
 */
object TeamGroupManager {

    @Volatile
    private var api: VoicechatServerApi? = null

    private val groupsByInstance: MutableMap<GameInstance, MutableList<UUID>> =
        Collections.synchronizedMap(WeakHashMap())

    fun attach(serverApi: VoicechatServerApi) {
        api = serverApi
    }

    fun isReady(): Boolean = api != null

    fun assignTeamsToGroups(instance: GameInstance) {
        val api = this.api ?: return

        val cfg = MiniGamesCore.configuration
        val typeStr = cfg.get(ConfigKeys.VOICECHAT_GROUP_TYPE).uppercase()
        val groupType = parseGroupType(typeStr)
        val pattern = cfg.get(ConfigKeys.VOICECHAT_GROUP_NAME_PATTERN)
        val persistent = cfg.get(ConfigKeys.VOICECHAT_GROUP_PERSISTENT)

        val createdIds = mutableListOf<UUID>()

        instance.teams.forEachIndexed { teamIndex, players ->
            if (players.isEmpty()) return@forEachIndexed

            val groupName = formatGroupName(pattern, teamIndex)
            val group: Group = api.groupBuilder()
                .setName(groupName)
                .setPersistent(persistent)
                .setType(groupType)
                .build()

            createdIds += group.id

            for (player in players) {
                val connection = api.getConnectionOf(player.uniqueId) ?: continue
                if (!connection.isInstalled) continue
                runCatching { connection.setGroup(group) }
                    .onFailure {
                        MiniGamesCore.plugin.logger.warning(
                            "[MiniGamesAPI] Failed to put ${player.name} into voice group $groupName: ${it.message}"
                        )
                    }
            }
        }

        if (createdIds.isNotEmpty()) {
            groupsByInstance[instance] = createdIds
        }
    }

    fun dissolveGroups(instance: GameInstance) {
        val api = this.api ?: return
        val groupIds = groupsByInstance.remove(instance) ?: return

        // Remove every player who could still be in one of our groups.
        // For non-persistent groups SVC will auto-delete the group once empty.
        val candidates = mutableSetOf<UUID>()
        candidates += instance.getActivePlayerIds()
        for (team in instance.teams) for (p in team) candidates += p.uniqueId

        for (uuid in candidates) {
            val connection = api.getConnectionOf(uuid) ?: continue
            val current = connection.group ?: continue
            if (current.id !in groupIds) continue
            runCatching { connection.setGroup(null) }
        }

        // For persistent groups we have to remove them explicitly.
        if (MiniGamesCore.configuration.get(ConfigKeys.VOICECHAT_GROUP_PERSISTENT)) {
            for (id in groupIds) {
                runCatching { api.removeGroup(id) }
            }
        }
    }

    private fun parseGroupType(value: String): Group.Type = when (value) {
        "OPEN" -> Group.Type.OPEN
        "ISOLATED" -> Group.Type.ISOLATED
        else -> Group.Type.NORMAL
    }

    private fun formatGroupName(pattern: String, teamIndex: Int): String {
        val teamNumber = teamIndex + 1
        val teamName = lookupTeamName(teamNumber)
        val modeDisplay = MiniGamesCore.configuration.get(ConfigKeys.MODE_DISPLAY_NAME)
        // Strip color codes — group names are plain text in SVC UI.
        val raw = pattern
            .replace("{mode_display}", modeDisplay)
            .replace("{team_index}", teamNumber.toString())
            .replace("{team_name}", teamName)
        return stripColorCodes(raw).take(MAX_GROUP_NAME_LENGTH)
    }

    private fun lookupTeamName(teamNumber: Int): String {
        val configured = Messages.getString("ui.teamselect.teams.$teamNumber.name")
        if (!configured.isNullOrBlank()) return stripColorCodes(configured)
        return "Команда $teamNumber"
    }

    private fun stripColorCodes(input: String): String {
        // Drop legacy &x and ChatColor section-x codes; SVC group name is plain text.
        return input.replace(LEGACY_COLOR_CODE, "")
    }

    private val LEGACY_COLOR_CODE = Regex("[§&][0-9a-fA-FklmnorxKLMNORX]")
    private const val MAX_GROUP_NAME_LENGTH = 32
}
