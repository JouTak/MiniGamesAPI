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
import ru.joutak.minigames.results.ResultsManager
import ru.joutak.minigames.spartakiad.SpartakiadManager
import ru.joutak.minigames.spartakiad.participant.storage.SqliteParticipantStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.WhitelistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlTeamlistStorage
import ru.joutak.minigames.spartakiad.whitelist.storage.YamlWhitelistStorage
import ru.joutak.minigames.util.uuid.BukkitUuidResolver
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import ru.joutak.minigames.util.uuid.UuidResolver
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    /** Root folder of the host plugin (mode) data. */
    private val dataPath: Path
        get() = plugin.dataFolder.toPath()

    /** Directory for MiniGamesAPI data inside the host plugin's data folder: plugins/<HostPlugin>/minigamesapi/ */
    val apiDataPath: Path
        get() = dataPath.resolve("minigamesapi")

    /** MiniGamesAPI config file: plugins/<HostPlugin>/minigamesapi/config.yml */
    val apiConfigFile: File
        get() = apiDataPath.resolve("config.yml").toFile()

    /** MiniGamesAPI results config file: plugins/<HostPlugin>/minigamesapi/results.yml */
    val apiResultsFile: File
        get() = apiDataPath.resolve("results.yml").toFile()

    fun initialize(plugin: JavaPlugin) {
        if (initialized) {
            plugin.logger.info("MiniGamesCore уже инициализирован, пропускаю повторный initialize().")
            return
        }
        initialized = true

        this.plugin = plugin

        plugin.logger.info("=== MiniGamesCore.initialize() START ===")

        plugin.dataFolder.mkdirs()
        apiDataPath.toFile().mkdirs()

        loadConfiguration()

        // Results are global for all modes; always initialize (it will be disabled by config by default).
        initResultsManager()

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

    private fun loadConfiguration() {
        plugin.logger.info("Loading MiniGamesAPI config...")

        val configPath = apiDataPath.resolve("config.yml")
        val rootHostConfig = dataPath.resolve("config.yml")

        if (!apiDataPath.exists()) {
            Files.createDirectories(apiDataPath)
        }

        // If the API config already exists but looks like a foreign minigame config (Splatoon/CW/etc) —
        // back it up and restore the default API config.
        if (configPath.exists() && !isLikelyApiConfig(configPath)) {
            try {
                val backup = apiDataPath.resolve("config.yml.bak_${System.currentTimeMillis()}")
                Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING)
                plugin.logger.warning(
                    "MiniGamesAPI config at $configPath does not look like MiniGamesAPI config. " +
                        "Backed up to $backup and restoring default config."
                )
                saveClasspathResourceOrDefault("minigamesapi/config.yml", configPath)
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to restore MiniGamesAPI config: ${t.message}")
                plugin.logger.severe(t.stackTraceToString())
            }
        }

        // Migration (VERY SAFE): old versions stored API config at plugins/<HostPlugin>/config.yml.
        // When API is shaded into a mode plugin, that file is almost always the mode's own config,
        // so we migrate ONLY if the root config has the explicit MiniGamesAPI marker.
        if (!configPath.exists() && rootHostConfig.exists() && rootHasApiMarker(rootHostConfig)) {
            try {
                plugin.logger.warning(
                    "Found legacy MiniGamesAPI config at $rootHostConfig. Copying it to $configPath (new location)."
                )
                Files.copy(rootHostConfig, configPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to migrate legacy MiniGamesAPI config: ${t.message}")
                plugin.logger.severe(t.stackTraceToString())
            }
        }

        if (!configPath.exists()) {
            plugin.logger.info("MiniGamesAPI config.yml not found at $configPath, creating default...")
            saveClasspathResourceOrDefault("minigamesapi/config.yml", configPath)
        }

        plugin.logger.info("MiniGamesAPI config path: $configPath (exists=${configPath.exists()})")

        val storage = YamlConfigStorage(configPath.toFile())
        configuration = Config(storage)

        // Ensure lobby items config is in memory before players click.
        LobbyItemsManager.reloadFromConfig()

        plugin.logger.info("MiniGamesAPI config loaded successfully.")
    }


    private fun rootHasApiMarker(path: Path): Boolean {
        return try {
            if (!path.exists()) return false
            val yaml = YamlConfiguration.loadConfiguration(path.toFile())
            yaml.contains("minigamesapi.config_version")
        } catch (_: Throwable) {
            false
        }
    }

    private fun isLikelyApiConfig(path: Path): Boolean {
        return try {
            if (!path.exists()) return false
            val yaml = YamlConfiguration.loadConfiguration(path.toFile())

            // Strong marker (present in our default config).
            if (yaml.contains("minigamesapi.config_version")) return true

            val hasApiSections =
                yaml.contains("mode") ||
                    yaml.contains("matchmaking") ||
                    yaml.contains("lobby") ||
                    yaml.contains("teamselect") ||
                    yaml.contains("spartakiad")

            // Very common minigame keys (Splatoon-style configs).
            val looksLikeMinigame =
                yaml.contains("map_name") ||
                    yaml.contains("lobby_name") ||
                    yaml.contains("arenas") ||
                    yaml.contains("boost_locations")

            hasApiSections && !looksLikeMinigame
        } catch (_: Throwable) {
            false
        }
    }

    private fun saveClasspathResourceOrDefault(resourcePath: String, targetPath: Path) {
        if (!targetPath.parent.exists()) {
            Files.createDirectories(targetPath.parent)
        }

        val stream = MiniGamesCore::class.java.classLoader.getResourceAsStream(resourcePath)
        if (stream != null) {
            stream.use { input ->
                Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            return
        }

        // Fallback: generate a minimal default config if the resource is not packaged (shading issues).
        val content = when (resourcePath) {
            "minigamesapi/config.yml" -> DEFAULT_API_CONFIG
            "minigamesapi/results.yml" -> DEFAULT_RESULTS_CONFIG
            else -> ""
        }

        Files.writeString(targetPath, content, StandardCharsets.UTF_8)
    }

    private fun initResultsManager() {
        try {
            // Ensure file exists even when results are disabled.
            if (!apiResultsFile.exists()) {
                saveClasspathResourceOrDefault("minigamesapi/results.yml", apiResultsFile.toPath())
            }

            ResultsManager.initialize(
                plugin = plugin,
                resultsFile = apiResultsFile,
                modeKeyProvider = { configuration.get(ConfigKeys.MODE_NAME) },
            )
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to initialize ResultsManager: ${t.message}")
            plugin.logger.severe(t.stackTraceToString())
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
        val minigameName = configuration.get(ConfigKeys.SPARTAKIAD_MINIGAME_NAME)

        val gamePath = apiDataPath.resolve(minigameName)
        gamePath.toFile().mkdirs()

        val whitelistPath = apiDataPath.resolve("whitelist.yml")
        if (!whitelistPath.exists()) {
            try {
                YamlConfiguration().save(whitelistPath.toFile())
            } catch (_: Throwable) {
            }
        }

        val teamMode = configuration.get(ConfigKeys.SPARTAKIAD_TEAM_MODE)

        val whitelistStorage: WhitelistStorage =
            if (teamMode) {
                YamlTeamlistStorage(whitelistPath.toFile())
            } else {
                YamlWhitelistStorage(whitelistPath.toFile())
            }

        val usingLibreLogin = configuration.get(ConfigKeys.USE_LIBRE_LOGIN)

        val uuidResolver: UuidResolver =
            if (usingLibreLogin && libreLogin != null) {
                try {
                    val ctor = LibreLoginUuidResolver::class.java.getConstructor(Any::class.java)
                    ctor.newInstance(libreLogin) as UuidResolver
                } catch (_: Exception) {
                    BukkitUuidResolver()
                }
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

        Bukkit.getPluginManager().registerEvents(LobbyItemsListener, plugin)

        Bukkit.getPluginManager().registerEvents(PlayerJoinListener, plugin)
        Bukkit.getPluginManager().registerEvents(PlayerQuitListener, plugin)
    }

    private fun registerCommands() {
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
    }

    fun shutdown() {
        plugin.logger.info("MiniGamesCore.shutdown()")

        try {
            configuration.close()
        } catch (_: Throwable) {
        }

        try {
            spartakiadManager.close()
        } catch (_: Throwable) {
        }

        ResultsManager.shutdown()
    }

    private const val DEFAULT_API_CONFIG: String = """
minigamesapi:
  config_version: 1

mode:
  # Logical mode name, used as mode_key for results DB and other cross-mode integrations.
  name: "minigame"

uuid:
  # Use LibreLogin UUID resolver via reflection (fallback to Bukkit UUID if LibreLogin is missing).
  use_libre_login: true

storage:
  debounce_millis: 500
  close_timeout_millis: 5000

spartakiad:
  enabled: false
  minigame_name: "minigame"
  attempts: 5
  team_mode: false

matchmaking:
  start:
    enabled: false
    min_fill_percent: 1.0
    min_teams: 2
    delay_seconds: 0
    announce:
      enabled: true
      interval_seconds: 5
      last_seconds_always: 5
      message: "&eДо начала игры &6{seconds}&e сек. Если не наберётся полный матч (&6{current}&e/&6{max}&e, команд &6{teams_current}&e/&6{teams_required}&e) — стартуем."
      cancelled_message: "&cСтарт отменён: недостаточно игроков или команд."
      ready_message: "&aМатч стартует неполным составом (&f{current}&a/&f{max}&a, команд &f{teams_current}&a/&f{teams_required}&a)."

lobby:
  items:
    enabled: true
    hotbar:
      - id: quick_ready
        enabled: true
        slot: 2
        material: EMERALD
        name: "&aГотов"
        lore:
          - "&7Быстро встать в очередь"
        action:
          type: READY
      - id: team_select
        enabled: true
        slot: 4
        material: NETHER_STAR
        name: "&bВыбор команды"
        lore:
          - "&7Открыть меню выбора команды"
        action:
          type: TEAM_SELECT
      - id: lobby_return
        enabled: true
        slot: 6
        material: COMPASS
        name: "&eВ лобби"
        lore:
          - "&7Вернуться в лобби"
        action:
          type: COMMAND
          command: "lobby"
          deny_message: "&cКоманда недоступна."

teamselect:
  title: "&8Выбор команды"
  teams:
    "1": { name: "&cКоманда 1", material: RED_WOOL, color: RED }
    "2": { name: "&eКоманда 2", material: YELLOW_WOOL, color: YELLOW }
    "3": { name: "&aКоманда 3", material: GREEN_WOOL, color: GREEN }
    "4": { name: "&9Команда 4", material: BLUE_WOOL, color: BLUE }
    "5": { name: "&bКоманда 5", material: CYAN_WOOL, color: AQUA }
    "6": { name: "&dКоманда 6", material: PURPLE_WOOL, color: LIGHT_PURPLE }
    "7": { name: "&6Команда 7", material: ORANGE_WOOL, color: GOLD }
    "8": { name: "&3Команда 8", material: LIGHT_BLUE_WOOL, color: DARK_AQUA }
    "9": { name: "&fКоманда 9", material: WHITE_WOOL, color: WHITE }
    "10": { name: "&0Команда 10", material: BLACK_WOOL, color: BLACK }
    "11": { name: "&5Команда 11", material: MAGENTA_WOOL, color: DARK_PURPLE }
    "12": { name: "&2Команда 12", material: LIME_WOOL, color: DARK_GREEN }
    "13": { name: "&dКоманда 13", material: PINK_WOOL, color: LIGHT_PURPLE }
    "14": { name: "&4Команда 14", material: BROWN_WOOL, color: DARK_RED }
    "15": { name: "&7Команда 15", material: GRAY_WOOL, color: GRAY }
    "16": { name: "&8Команда 16", material: LIGHT_GRAY_WOOL, color: DARK_GRAY }
"""

    private const val DEFAULT_RESULTS_CONFIG: String = """
results:
  enabled: false
  server_id: "server-1"
  schema:
    auto_create: true
  jdbc:
    url: ""
    username: ""
    password: ""
    driver: ""
    connect_timeout_seconds: 5
"""
}
