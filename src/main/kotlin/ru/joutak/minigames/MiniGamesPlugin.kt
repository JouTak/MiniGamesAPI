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
import ru.joutak.minigames.config.provider.YamlConfigProvider
import ru.joutak.minigames.listener.PlayerLoginListener
import ru.joutak.minigames.spartakiad.SpartakiadManager
import ru.joutak.minigames.spartakiad.participant.provider.YamlParticipantsProvider
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import xyz.kyngs.librelogin.api.LibreLoginPlugin
import xyz.kyngs.librelogin.api.provider.LibreLoginProvider
import kotlin.io.path.exists
import kotlin.io.path.name

class MiniGamesPlugin : JavaPlugin() {
    companion object {
        @JvmStatic
        lateinit var instance: MiniGamesPlugin
    }

    private lateinit var configuration: Config
    private lateinit var spartakiadManager: SpartakiadManager
    private lateinit var libreLogin: LibreLoginPlugin<Player, World>

    /**
     * Plugin startup logic
     */
    override fun onEnable() {
        instance = this

        loadConfiguration()
        loadDependencies()
        initSpartakiadManager()
        registerEvents()
        registerCommands()

        logger.info("Плагин ${pluginMeta.name} версии ${pluginMeta.version} включен!")
    }

    private fun loadConfiguration() {
        val configPath = dataPath.resolve("config.yml")
        if (!configPath.exists()) {
            logger.warning("Не найден файл c конфигом ${configPath.name}! Создан файл со стандартными значениями.")
            saveResource(configPath.name, false)
        }

        val configProvider = YamlConfigProvider(configPath.toFile())
        configuration = Config(configProvider)
    }

    private fun loadDependencies() {
        val libreLoginProvider = Bukkit.getPluginManager().getPlugin("LibreLogin") as LibreLoginProvider<Player, World>?
        if (libreLoginProvider == null) {
            logger.severe("Не удалось получить доступ к API LibreLogin!")
            server.pluginManager.disablePlugin(this)
        }
        libreLogin = libreLoginProvider!!.libreLogin
    }

    private fun initSpartakiadManager() {
        val participantsPath = dataPath.resolve("participants.yml")
        if (!participantsPath.exists()) {
            logger.warning("Не найден файл с участниками ${participantsPath.name}! Создан файл с пустым списком.")
            YamlConfiguration().save(participantsPath.toFile())
        }

        val participantsProvider = YamlParticipantsProvider(participantsPath.toFile())
        val uuidResolver = LibreLoginUuidResolver(libreLogin)
        spartakiadManager = SpartakiadManager(participantsProvider, uuidResolver)
    }

    private fun registerEvents() {
        Bukkit.getPluginManager().registerEvents(PlayerLoginListener, this)
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
        saveConfiguration()
    }

    private fun saveConfiguration() {
        configuration.save()
    }

    fun getConfiguration(): Config = configuration

    fun getSpartakiadManager() = spartakiadManager
}
