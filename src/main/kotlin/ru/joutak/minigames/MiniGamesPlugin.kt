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
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

class MiniGamesPlugin : JavaPlugin() {
    companion object {
        @JvmStatic
        lateinit var instance: MiniGamesPlugin
    }

    lateinit var configuration: Config
        private set
    lateinit var spartakiadManager: SpartakiadManager
        private set
    private var libreLogin: LibreLoginPlugin<Player, World>? = null

    /**
     * Plugin startup logic
     */
    override fun onEnable() {
        instance = this

        registerDomains()
        loadConfiguration()
        loadDependencies()
        initSpartakiadManager()
        registerEvents()
        registerCommands()

        logger.info("Плагин ${pluginMeta.name} версии ${pluginMeta.version} включен!")
    }

    private fun registerDomains() {
        // ConfigurationSerialization.registerClass(Participant::class.java, "Participant")
    }

    private fun loadConfiguration() {
        val configPath = dataPath.resolve("config.yml")
        if (!configPath.exists()) {
            logger.warning("Не найден файл c конфигом ${configPath.name}!")
            saveResource(configPath.name, false)
        }

        val configStorage = YamlConfigStorage(configPath.toFile())
        configuration = Config(configStorage)
    }

    private fun loadDependencies() {
        if (configuration.get(ConfigKeys.USE_LIBRE_LOGIN)) {
            val libreLoginProvider =
                Bukkit.getPluginManager().getPlugin("LibreLogin") as LibreLoginProvider<Player, World>?
            if (libreLoginProvider == null) {
                logger.severe("Не удалось получить доступ к API LibreLogin!")
                server.pluginManager.disablePlugin(this)
            }
            libreLogin = libreLoginProvider!!.libreLogin
        }
    }

    private fun initSpartakiadManager() {
        val minigameName = configuration.get(ConfigKeys.SPARTAKIAD_MINIGAME_NAME)
        val gameDataPath = dataPath.resolve(minigameName)
        if (!gameDataPath.exists()) {
            gameDataPath.createDirectories()
        }

        val whitelistStorage: WhitelistStorage
        val whitelistPath = dataPath.resolve("whitelist.yml")
        if (!whitelistPath.exists()) {
            whitelistPath.createParentDirectories()
            YamlConfiguration().save(whitelistPath.toFile())
            logger.warning("Не найден файл с участниками ${whitelistPath.name}! Создан файл с пустым списком.")
        }

        whitelistStorage = if (configuration.get(ConfigKeys.SPARTAKIAD_TEAM_MODE)) {
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

        val participantsPath = gameDataPath.resolve("participants.db")
        if (!participantsPath.exists()) {
            participantsPath.createParentDirectories()
            logger.warning("Не найден файл с информацией об игроках ${participantsPath.name}!")
        }
        val participantStorage = SqliteParticipantStorage(participantsPath.toFile())

        spartakiadManager = SpartakiadManager(gameDataPath, participantStorage, whitelistStorage, uuidResolver)
    }

    private fun registerEvents() {
        Bukkit.getPluginManager().registerEvents(AsyncPlayerPreLoginListener, this)
        Bukkit.getPluginManager().registerEvents(WhitelistReloadListener, this)
        Bukkit.getPluginManager().registerEvents(WhitelistChangeListener, this)
    }

    private fun registerCommands() {
        val readyCommand = ReadyCommand.getBuilder().build()

        val mainCommand =
            MiniGamesCommand
                .getBuilder()
                .then(StartCommand.getBuilder())
                .build()

        this.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
            commands.registrar().register(readyCommand)
            commands.registrar().register(mainCommand)
        }
    }

    /**
     * Plugin shutdown logic
     */
    override fun onDisable() {
        configuration.close()
        spartakiadManager.close()
    }
}
