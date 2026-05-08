package ru.joutak.minigames.integration.voicechat

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.JoinGroupEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import ru.joutak.minigames.MiniGamesCore

/**
 * SimpleVoiceChat plugin entry point.
 *
 * Loaded ONLY when SVC is present on the server (see [VoiceChatIntegration]).
 * Imports of `de.maxhenkel.*` MUST stay inside this package — JVM will not try
 * to resolve them on servers without SVC as long as no loaded class references them.
 */
object VoiceChatHook : VoicechatPlugin {

    override fun getPluginId(): String = "minigamesapi"

    override fun initialize(api: VoicechatApi) {
        // Server API is only valid after VoicechatServerStartedEvent — see registerEvents.
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { event ->
            val serverApi: VoicechatServerApi = event.voicechat
            TeamGroupManager.attach(serverApi)
            MiniGamesCore.plugin.logger.info(
                "[MiniGamesAPI] SimpleVoiceChat server API attached"
            )
        }

        registration.registerEvent(JoinGroupEvent::class.java) { event ->
            val groupId = event.group?.id ?: return@registerEvent
            val instance = TeamGroupManager.getOwnerOf(groupId) ?: return@registerEvent
            val uuid = event.connection?.player?.uuid ?: return@registerEvent
            if (TeamGroupManager.isAllowedToJoin(uuid, groupId, instance)) return@registerEvent
            event.cancel()
        }
    }
}
