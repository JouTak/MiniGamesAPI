package ru.joutak.minigames

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.command.ForceRunCommand
import ru.joutak.minigames.command.mg.MiniGamesCommand
import ru.joutak.minigames.command.mg.StartCommand
import ru.joutak.minigames.command.ready.ReadyCommand
import ru.joutak.minigames.command.teamselect.TeamSelectCommand
import ru.joutak.minigames.command.unready.UnreadyCommand
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.storage.YamlConfigStorage
import ru.joutak.minigames.listener.AsyncPlayerPreLoginListener
import ru.joutak.minigames.listener.LobbyItemsListener
import ru.joutak.minigames.listener.PlayerJoinListener
import ru.joutak.minigames.listener.PlayerQuitListener
import ru.joutak.minigames.listener.WhitelistChangeListener
import ru.joutak.minigames.listener.WhitelistReloadListener
import ru.joutak.minigames.lobby.LobbyItemsManager
import ru.joutak.minigames.spartakiad.SpartakiadManager
import ru.joutak.minigames.spartakiad.participant.storage.SqliteParticipantStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.WhitelistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlTeamlistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlWhitelistStorage
import ru.joutak.minigames.util.uuid.BukkitUuidResolver
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import ru.joutak.minigames.util.uuid.UuidResolver
import java.nio.file.Path
import kotlin.io.path.exists

object MiniGamesCore {

    lateinit var configuration: Config
        private set

    lateinit var spartakiadManager: SpartakiadManager

    lateinit var plugin: JavaPlugin

    @Volatile
    private var initialized: Boolean = false

    // Динамически загружаемый LibreLogin
    private var libreLogin: Any? = null

    fun initialize(plugin: JavaPlugin) {
        if (initialized) {
            plugin.logger.info("MiniGamesCore уже инициализирован, пропускаю повторный initialize().")
            return
        }
        initialized = true

        this.plugin = plugin

        plugin.logger.info("=== MiniGamesCore.initialize() START ===")

        loadConfiguration()
        loadDependencies()
        initSpartakiadManager()
        registerEvents()
        registerCommands()

        // If server reloaded and players are already online, ensure lobby items are present.
        Bukkit.getOnlinePlayers().forEach { p ->
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (p.isOnline) {
                    LobbyItemsManager.ensure(p)
                }
            }, 1L)
        }

        MiniGamesAPI.initialize(plugin, configuration)
        plugin.logger.info("MiniGamesAPI встроена как библиотека")

        plugin.logger.info("=== MiniGamesCore.initialize() END ===")
    }
    private val dataPath: Path
        get() = plugin.dataFolder.toPath()

    /**
     * All MiniGamesAPI files live in a separate subfolder inside the host plugin's data folder:
     * plugins/<HostPlugin>/minigamesapi/...
     *
     * This avoids conflicts with the minigame's own config.yml and other files.
     */
    private val apiConfigDir: Path
        get() = dataPath.resolve("minigamesapi")

    private fun loadConfiguration() {
        plugin.logger.info("Loading MiniGamesAPI config...")

        // Ensure plugin data folder exists.
        plugin.dataFolder.mkdirs()

        val configDir = apiConfigDir
        configDir.toFile().mkdirs()

        val configPath = configDir.resolve("config.yml")
        val legacyPath = dataPath.resolve("config.yml")

        plugin.logger.info("MiniGamesAPI config path: $configPath (exists=${configPath.exists()})")

        // Migration: if old config.yml exists in the root data folder, copy it into the API subfolder.
        if (!configPath.exists() && legacyPath.exists()) {
            try {
                plugin.logger.warning("Found legacy config at $legacyPath. Copying it to $configPath (new location).")
                java.nio.file.Files.copy(
                    legacyPath,
                    configPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                plugin.logger.severe("Failed to copy legacy config.yml into API folder: $e")
            }
        }

        if (!configPath.exists()) {
            plugin.logger.info("MiniGamesAPI config.yml not found, saving default to $configPath ...")
            saveResourceFromApiJar("minigamesapi/config.yml", configPath)
        }

        val storage = YamlConfigStorage(configPath.toFile())
        configuration = Config(storage)

        plugin.logger.info("MiniGamesAPI config loaded successfully.")
    }

    private fun saveResourceFromApiJar(resourcePath: String, targetPath: Path) {
        val loader = MiniGamesCore::class.java.classLoader

        val directStream =
            loader.getResourceAsStream(resourcePath)
                ?: loader.getResourceAsStream(resourcePath.removePrefix("/"))

        // Backward-compat only for config.yml: older jars may still have it in the root.
        val stream =
            directStream ?: if (resourcePath.endsWith("config.yml")) {
                loader.getResourceAsStream("config.yml") ?: loader.getResourceAsStream("/config.yml")
            } else {
                null
            }

        if (stream == null) {
            plugin.logger.severe("Resource not found in jar (tried: $resourcePath). Creating a minimal file at $targetPath.")
            targetPath.toFile().parentFile?.mkdirs()

            if (resourcePath.endsWith("config.yml")) {
                targetPath.toFile().writeText("# MiniGamesAPI config (auto-generated)\n", Charsets.UTF_8)
            } else {
                targetPath.toFile().writeText("", Charsets.UTF_8)
            }
            return
        }

        stream.use { input ->
            targetPath.toFile().parentFile?.mkdirs()
            java.nio.file.Files.copy(
                input,
                targetPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }



    private fun loadDependencies() {
        val useLL = configuration.get(ConfigKeys.USE_LIBRE_LOGIN)
        plugin.logger.info("LibreLogin enabled in config: $useLL")

        if (!useLL) {
            plugin.logger.info("LibreLogin disabled. Skipping.")
            return
        }

        try {
            val providerClass = Class.forName("xyz.kyngs.librelogin.api.provider.LibreLoginProvider")
            val getInstance = providerClass.getMethod("getInstance")
            val provider = getInstance.invoke(null)

            val getLibreLogin = providerClass.getMethod("getLibreLogin")
            libreLogin = getLibreLogin.invoke(provider)

            plugin.logger.info("Successfully loaded LibreLogin dynamically: $libreLogin")

        } catch (_: ClassNotFoundException) {
            plugin.logger.severe("LibreLogin not found! Disabling LibreLogin integration.")
            libreLogin = null
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load LibreLogin dynamically: $e")
            libreLogin = null
        }
    }

    private fun initSpartakiadManager() {
        plugin.logger.info("=== initSpartakiadManager() ===")

        val minigameName = configuration.get(ConfigKeys.SPARTAKIAD_MINIGAME_NAME)
        plugin.logger.info("minigameName = $minigameName")

        val apiDir = apiConfigDir
        apiDir.toFile().mkdirs()

        val gamePath = apiDir.resolve(minigameName)
        val legacyGamePath = dataPath.resolve(minigameName)

        // Migration: previously Spartakiad files lived in the root data folder.
        if (!gamePath.exists() && legacyGamePath.exists()) {
            try {
                plugin.logger.warning("Found legacy Spartakiad folder at $legacyGamePath. Copying it to $gamePath (new location).")
                gamePath.toFile().mkdirs()

                val legacyDb = legacyGamePath.resolve("participants.db")
                val newDb = gamePath.resolve("participants.db")
                if (legacyDb.exists() && !newDb.exists()) {
                    java.nio.file.Files.copy(
                        legacyDb,
                        newDb,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (e: Exception) {
                plugin.logger.severe("Failed to migrate Spartakiad data into API folder: $e")
            }
        }

        gamePath.toFile().mkdirs()
        plugin.logger.info("gamePath = $gamePath")

        val teamMode = configuration.get(ConfigKeys.SPARTAKIAD_TEAM_MODE)
        plugin.logger.info("team_mode = $teamMode")

        val whitelistPath = apiDir.resolve("whitelist.yml")
        val legacyWhitelistPath = dataPath.resolve("whitelist.yml")

        if (!whitelistPath.exists() && legacyWhitelistPath.exists()) {
            try {
                plugin.logger.warning("Found legacy whitelist at $legacyWhitelistPath. Copying it to $whitelistPath (new location).")
                java.nio.file.Files.copy(
                    legacyWhitelistPath,
                    whitelistPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                plugin.logger.severe("Failed to copy legacy whitelist.yml into API folder: $e")
            }
        }

        if (!whitelistPath.exists()) {
            plugin.logger.info("whitelist.yml not found in API folder, creating empty...")
            val yaml = YamlConfiguration()
            if (teamMode) {
                yaml.set("teams", mapOf<String, Any>())
            } else {
                yaml.set("participants", emptyList<String>())
            }
            yaml.save(whitelistPath.toFile())
        }

        plugin.logger.info("whitelistPath = $whitelistPath (exists=${whitelistPath.exists()})")

        val whitelistStorage: WhitelistStorage =
            if (teamMode) {
                plugin.logger.info("Using YamlTeamlistStorage")
                YamlTeamlistStorage(whitelistPath.toFile())
            } else {
                plugin.logger.info("Using YamlWhitelistStorage")
                YamlWhitelistStorage(whitelistPath.toFile())
            }

        val usingLibreLogin = configuration.get(ConfigKeys.USE_LIBRE_LOGIN)
        plugin.logger.info("use_libre_login = $usingLibreLogin")
        plugin.logger.info("libreLogin = $libreLogin")

        val uuidResolver: UuidResolver =
            if (usingLibreLogin && libreLogin != null) {
                plugin.logger.info("Creating LibreLoginUuidResolver (reflection)")
                try {
                    val ctor = LibreLoginUuidResolver::class.java.getConstructor(Any::class.java)
                    ctor.newInstance(libreLogin) as UuidResolver
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to create LibreLoginUuidResolver: $e")
                    BukkitUuidResolver()
                }
            } else {
                plugin.logger.info("Creating BukkitUuidResolver (LL disabled or missing)")
                BukkitUuidResolver()
            }

        val dbFile = gamePath.resolve("participants.db").toFile()
        plugin.logger.info("participants.db = ${dbFile.absolutePath}")

        val participantStorage = SqliteParticipantStorage(dbFile)
        plugin.logger.info("SqliteParticipantStorage created.")

        spartakiadManager =
            SpartakiadManager(
                gamePath,
                participantStorage,
                whitelistStorage,
                uuidResolver
            )

        plugin.logger.info("SpartakiadManager created successfully!")
    }

    private fun registerEvents() {
        plugin.logger.info("Registering events...")

        Bukkit.getPluginManager().registerEvents(AsyncPlayerPreLoginListener, plugin)
        Bukkit.getPluginManager().registerEvents(WhitelistChangeListener, plugin)
        Bukkit.getPluginManager().registerEvents(WhitelistReloadListener, plugin)

        // Lobby UX
        Bukkit.getPluginManager().registerEvents(LobbyItemsListener, plugin)

        // Helpful join message / cleanup
        Bukkit.getPluginManager().registerEvents(PlayerJoinListener, plugin)
        Bukkit.getPluginManager().registerEvents(PlayerQuitListener, plugin)

        plugin.logger.info("Events registered.")
    }

    private fun registerCommands() {
        plugin.logger.info("Registering commands...")

        val readyCmd = ReadyCommand.getBuilder().build()
        val teamSelectCmd = TeamSelectCommand.getBuilder().build()
        val unreadyCmd = UnreadyCommand.getBuilder().build()
        val mgCmd = MiniGamesCommand.getBuilder().then(StartCommand.getBuilder()).build()
        val forceRunCmd = ForceRunCommand.getBuilder().build()

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(readyCmd)
            event.registrar().register(teamSelectCmd)
            event.registrar().register(unreadyCmd)
            event.registrar().register(mgCmd)
            event.registrar().register(forceRunCmd)
        }

        plugin.logger.info("Commands registered.")
    }

    fun shutdown() {
        plugin.logger.info("MiniGamesCore.shutdown()")

        configuration.close()
        spartakiadManager.close()
    }
}
