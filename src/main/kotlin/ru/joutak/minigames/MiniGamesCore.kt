package ru.joutak.minigames

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.command.mg.MiniGamesCommand
import ru.joutak.minigames.command.mg.StartCommand
import ru.joutak.minigames.command.ready.ReadyCommand
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.storage.YamlConfigStorage
import ru.joutak.minigames.listener.AsyncPlayerPreLoginListener
import ru.joutak.minigames.listener.WhitelistChangeListener
import ru.joutak.minigames.listener.WhitelistReloadListener
import ru.joutak.minigames.spartakiad.SpartakiadManager
import ru.joutak.minigames.spartakiad.participant.storage.SqliteParticipantStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.WhitelistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlTeamlistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlWhitelistStorage
import ru.joutak.minigames.util.uuid.BukkitUuidResolver
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import xyz.kyngs.librelogin.api.LibreLoginPlugin
import xyz.kyngs.librelogin.api.provider.LibreLoginProvider
import kotlin.io.path.*
import java.nio.file.Path

object MiniGamesCore {

    lateinit var configuration: Config
        private set

    lateinit var spartakiadManager: SpartakiadManager

    lateinit var plugin: JavaPlugin

    fun initialize(plugin: JavaPlugin) {
        this.plugin = plugin

        loadConfiguration()
        loadDependencies()
        initSpartakiadManager()
        registerEvents()
        registerCommands()

        MiniGamesAPI.initialize(plugin, configuration)
        plugin.logger.info("MiniGamesAPI встроена как библиотека")
    }

    private val dataPath: Path
        get() = plugin.dataFolder.toPath()

    private var libreLogin: LibreLoginPlugin<Player, World>? = null

    private fun loadConfiguration() {
        val configPath = dataPath.resolve("config.yml")
        if (!configPath.exists()) {
            plugin.saveResource("config.yml", false)
        }

        val storage = YamlConfigStorage(configPath.toFile())
        configuration = Config(storage)
    }

    private fun loadDependencies() {
        if (configuration.get(ConfigKeys.USE_LIBRE_LOGIN)) {
            val provider = Bukkit.getPluginManager()
                .getPlugin("LibreLogin") as? LibreLoginProvider<Player, World>

            libreLogin = provider?.libreLogin
        }
    }

    private fun initSpartakiadManager() {
        val minigameName = configuration.get(ConfigKeys.SPARTAKIAD_MINIGAME_NAME)
        val gamePath = dataPath.resolve(minigameName).apply { toFile().mkdirs() }

        val whitelistPath = dataPath.resolve("whitelist.yml")
        if (!whitelistPath.exists()) {
            YamlConfiguration().save(whitelistPath.toFile())
        }

        val whitelistStorage: WhitelistStorage =
            if (configuration.get(ConfigKeys.SPARTAKIAD_TEAM_MODE)) {
                YamlTeamlistStorage(whitelistPath.toFile())
            } else {
                YamlWhitelistStorage(whitelistPath.toFile())
            }

        val uuidResolver =
            if (configuration.get(ConfigKeys.USE_LIBRE_LOGIN)) {
                LibreLoginUuidResolver(libreLogin!!)
            } else {
                BukkitUuidResolver()
            }

        val dbFile = gamePath.resolve("participants.db").toFile()
        val participantStorage = SqliteParticipantStorage(dbFile)

        spartakiadManager =
            SpartakiadManager(
                gamePath,
                participantStorage,
                whitelistStorage,
                uuidResolver
            )
    }

    private fun registerEvents() {
        Bukkit.getPluginManager().registerEvents(AsyncPlayerPreLoginListener, plugin)
        Bukkit.getPluginManager().registerEvents(WhitelistChangeListener, plugin)
        Bukkit.getPluginManager().registerEvents(WhitelistReloadListener, plugin)
    }

    private fun registerCommands() {
        val readyCmd = ReadyCommand.getBuilder().build()
        val mgCmd = MiniGamesCommand.getBuilder().then(StartCommand.getBuilder()).build()

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(readyCmd)
            event.registrar().register(mgCmd)
        }
    }

    fun shutdown() {
        configuration.close()
        spartakiadManager.close()
    }
}
