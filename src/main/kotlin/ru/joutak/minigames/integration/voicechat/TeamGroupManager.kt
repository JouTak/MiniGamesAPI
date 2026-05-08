package ru.joutak.minigames.integration.voicechat

import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatServerApi
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.results.model.MatchResult
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Lifecycle:
 *  - on [GameInstanceReadyEvent][ru.joutak.minigames.event.GameInstanceReadyEvent] —
 *    [assignTeamsToGroups] creates one voice group per team and puts roster into it.
 *  - on [MatchResultRecordingEvent][ru.joutak.minigames.event.MatchResultRecordingEvent]
 *    — [dissolveGroupsForResult] tears the groups down (called when a mode
 *    invokes `MiniGamesAPI.recordMatchResult`).
 *  - on [GameInstanceEndedEvent][ru.joutak.minigames.event.GameInstanceEndedEvent]
 *    — [dissolveGroups] is the safety net for modes that don't call
 *    `recordMatchResult`.
 *
 * Access control: only original team members (their own group) and
 * registered spectators (any group of the same instance) are allowed in.
 * Outsiders are stopped at SVC's [JoinGroupEvent] (handled in [VoiceChatHook]).
 */
object TeamGroupManager {

    @Volatile
    private var api: VoicechatServerApi? = null

    private val groupsByInstance: MutableMap<GameInstance, MutableList<UUID>> =
        Collections.synchronizedMap(WeakHashMap())

    /** group id → instance owning that group (reverse lookup for [JoinGroupEvent]). */
    private val groupOwnerByGroupId: MutableMap<UUID, GameInstance> =
        Collections.synchronizedMap(mutableMapOf())

    /** player uuid → the group id they were originally placed in (their own team's). */
    private val playerOriginalGroup: MutableMap<UUID, UUID> =
        Collections.synchronizedMap(mutableMapOf())

    /** instance → set of player uuids allowed in ANY of that instance's groups. */
    private val spectatorAccess: MutableMap<GameInstance, MutableSet<UUID>> =
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

            // IMPORTANT ordering: register access entries BEFORE setGroup() —
            // SVC fires JoinGroupEvent synchronously, our guard must already
            // know the player is allowed.
            groupOwnerByGroupId[group.id] = instance
            for (player in players) {
                playerOriginalGroup[player.uniqueId] = group.id
            }

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

    /** Tear down groups for the instance the [result]'s players currently occupy. */
    fun dissolveGroupsForResult(result: MatchResult) {
        if (api == null) return
        val playerIds = result.players.mapTo(mutableSetOf()) { it.playerUuid }
        if (playerIds.isEmpty()) return

        // Snapshot keys: dissolveGroups removes from groupsByInstance and we
        // don't want to mutate while iterating.
        val keys = synchronized(groupsByInstance) { groupsByInstance.keys.toList() }
        val match = keys.firstOrNull { inst ->
            // Match by either currently-active participants or by who was originally
            // placed into this instance's groups (some modes wipe activePlayerIds early).
            val groupIds = groupsByInstance[inst] ?: emptyList()
            val originalUuids = synchronized(playerOriginalGroup) {
                playerOriginalGroup.filterValues { it in groupIds }.keys.toSet()
            }
            playerIds.any { it in inst.getActivePlayerIds() } || playerIds.any { it in originalUuids }
        } ?: return

        dissolveGroups(match)
    }

    fun dissolveGroups(instance: GameInstance) {
        val api = this.api ?: return
        val groupIds = groupsByInstance.remove(instance) ?: return

        // Remove every player who could still be in one of our groups.
        // For non-persistent groups SVC will auto-delete the group once empty.
        val candidates = mutableSetOf<UUID>()
        candidates += instance.getActivePlayerIds()
        for (team in instance.teams) for (p in team) candidates += p.uniqueId
        // Include players we tracked as original members (in case mode cleared teams).
        synchronized(playerOriginalGroup) {
            playerOriginalGroup.forEach { (uuid, groupId) ->
                if (groupId in groupIds) candidates += uuid
            }
        }
        synchronized(spectatorAccess) {
            spectatorAccess[instance]?.let { candidates += it }
        }

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

        // Tidy access maps so stale entries don't accumulate.
        groupIds.forEach { groupOwnerByGroupId.remove(it) }
        synchronized(playerOriginalGroup) {
            playerOriginalGroup.entries.removeIf { it.value in groupIds }
        }
        spectatorAccess.remove(instance)
    }

    // --- access control surface (called by VoiceChatHook + VoiceSpectatorRegistry impl) ---

    fun getOwnerOf(groupId: UUID): GameInstance? = groupOwnerByGroupId[groupId]

    fun isAllowedToJoin(uuid: UUID, groupId: UUID, instance: GameInstance): Boolean {
        if (playerOriginalGroup[uuid] == groupId) return true
        val spectators = spectatorAccess[instance] ?: return false
        return uuid in spectators
    }

    fun addSpectator(instance: GameInstance, uuid: UUID) {
        synchronized(spectatorAccess) {
            spectatorAccess.getOrPut(instance) { mutableSetOf() }.add(uuid)
        }
    }

    fun removeSpectator(instance: GameInstance, uuid: UUID) {
        synchronized(spectatorAccess) {
            spectatorAccess[instance]?.remove(uuid)
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
        // Plain text already (no '&' codes) — exactly what SVC group names accept.
        // Single source of truth: config.yml: teamselect.teams.<n>.name, see TeamStyleProvider.
        return MiniGamesAPI.getTeamStyle(teamNumber).displayNamePlain
    }

    private fun stripColorCodes(input: String): String {
        // Drop legacy &x and ChatColor section-x codes; SVC group name is plain text.
        return input.replace(LEGACY_COLOR_CODE, "")
    }

    private val LEGACY_COLOR_CODE = Regex("[§&][0-9a-fA-FklmnorxKLMNORX]")
    private const val MAX_GROUP_NAME_LENGTH = 32
}
