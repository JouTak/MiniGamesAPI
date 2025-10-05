package ru.joutak.minigames

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.command.mg.MiniGamesCommand
import ru.joutak.minigames.command.mg.StartCommand
import ru.joutak.minigames.command.ready.ReadyCommand
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.provider.YamlConfigProvider
import ru.joutak.minigames.domain.PlayerData
import ru.joutak.minigames.listener.AsyncPlayerPreLoginListener
import ru.joutak.minigames.listener.ParticipantsListChangeListener
import ru.joutak.minigames.listener.ParticipantsListReloadListener
import ru.joutak.minigames.spartakiad.SpartakiadManager
import ru.joutak.minigames.spartakiad.participant.provider.YamlParticipantsProvider
import ru.joutak.minigames.spartakiad.playerData.storage.SqlitePlayerDataStorage
import ru.joutak.minigames.util.uuid.BukkitUuidResolver
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import xyz.kyngs.librelogin.api.LibreLoginPlugin
import xyz.kyngs.librelogin.api.provider.LibreLoginProvider
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
        ConfigurationSerialization.registerClass(PlayerData::class.java, "PlayerData")
    }

    private fun loadConfiguration() {
        val configPath = dataPath.resolve("config.yml")
        if (!configPath.exists()) {
            logger.warning("Не найден файл c конфигом ${configPath.name}!")
            saveResource(configPath.name, false)
        }

        val configProvider = YamlConfigProvider(configPath.toFile())
        configuration = Config(configProvider)
    }

    private fun loadDependencies() {
        if (configuration.get(ConfigKeys.USE_LIBRE_LOGIN)) {
            val libreLoginProvider = Bukkit.getPluginManager().getPlugin("LibreLogin") as LibreLoginProvider<Player, World>?
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
            gameDataPath.createParentDirectories()
        }

        val participantsPath = dataPath.resolve("participants.yml")
        if (!participantsPath.exists()) {
            participantsPath.createParentDirectories()
            YamlConfiguration().save(participantsPath.toFile())
            logger.warning("Не найден файл с участниками ${participantsPath.name}! Создан файл с пустым списком.")
        }
        val participantsProvider = YamlParticipantsProvider(participantsPath.toFile())
        val uuidResolver =
            if (configuration.get(ConfigKeys.USE_LIBRE_LOGIN)) {
                LibreLoginUuidResolver(libreLogin!!)
            } else {
                BukkitUuidResolver()
            }

        val playerDataPath = gameDataPath.resolve("playerData.db")
        if (!playerDataPath.exists()) {
            playerDataPath.createParentDirectories()
            logger.warning("Не найден файл с информацией об игроках ${playerDataPath.name}!")
        }
        val playerDataProvider = SqlitePlayerDataStorage(playerDataPath.toFile())

        spartakiadManager = SpartakiadManager(gameDataPath, playerDataProvider, participantsProvider, uuidResolver)
    }

    private fun registerEvents() {
        Bukkit.getPluginManager().registerEvents(AsyncPlayerPreLoginListener, this)
        Bukkit.getPluginManager().registerEvents(ParticipantsListReloadListener, this)
        Bukkit.getPluginManager().registerEvents(ParticipantsListChangeListener, this)
    }

    private fun registerCommands() {
        val readyCommand = ReadyCommand.getBuilder().build()

        val spartaCommand =
            MiniGamesCommand
                .getBuilder()
                .then(StartCommand.getBuilder())
                .build()

        this.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
            commands.registrar().register(readyCommand)
            commands.registrar().register(spartaCommand)
        }
    }

    /**
     * Plugin shutdown logic
     */
    override fun onDisable() {
        configuration.saveAndClose()
        spartakiadManager.close()
    }
}
