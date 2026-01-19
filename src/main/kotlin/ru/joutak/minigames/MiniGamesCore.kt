package ru.joutak.minigames

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.command.ForceRunCommand
import ru.joutak.minigames.command.forceready.ForceReadyCommand
import ru.joutak.minigames.command.mg.MiniGamesCommand
import ru.joutak.minigames.command.mg.StartCommand
import ru.joutak.minigames.command.ready.ReadyCommand
import ru.joutak.minigames.command.teamselect.TeamSelectCommand
import ru.joutak.minigames.command.tournament.TournamentCommand
import ru.joutak.minigames.command.unready.UnreadyCommand
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.config.storage.YamlConfigStorage
import ru.joutak.minigames.gui.TeamSelectionGui
import ru.joutak.minigames.listener.AsyncPlayerPreLoginListener
import ru.joutak.minigames.listener.LobbyItemsListener
import ru.joutak.minigames.listener.PlayerJoinListener
import ru.joutak.minigames.listener.PlayerQuitListener
import ru.joutak.minigames.lobby.LobbyItemsManager
import ru.joutak.minigames.results.ResultsManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager
import ru.joutak.minigames.util.uuid.BukkitUuidResolver
import ru.joutak.minigames.util.uuid.LibreLoginUuidResolver
import ru.joutak.minigames.util.uuid.UuidResolver
import ru.joutak.minigames.ui.LobbyScoreboardManager
import ru.joutak.minigames.tournament.TournamentManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

object MiniGamesCore {

    lateinit var configuration: Config
        private set


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

    /** MiniGamesAPI messages config file: plugins/<HostPlugin>/minigamesapi/messages.yml */
    val apiMessagesFile: File
        get() = apiDataPath.resolve("messages.yml").toFile()

    /** Tournament qualifier config file: plugins/<HostPlugin>/minigamesapi/tournament_qualifier.yml */
    val apiTournamentQualifierFile: File
        get() = apiDataPath.resolve("tournament_qualifier.yml").toFile()

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

        initTournamentManager()
        initTournamentQualifierManager()
        // Ceremony is implemented by each mode during its own ENDING/CLEANUP.

        loadDependencies()
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

        // Lobby sidebar scoreboard: show teams composition for the upcoming match.
        LobbyScoreboardManager.start()
        LobbyScoreboardManager.updateAll()

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

        // v2 migration: unify mode naming, add display name, drop old spartakiad.minigame_name.
        migrateApiConfigV2(configPath)
        migrateApiConfigV3(configPath)

        plugin.logger.info("MiniGamesAPI config path: $configPath (exists=${configPath.exists()})")

        val storage = YamlConfigStorage(configPath.toFile())
        configuration = Config(storage)

        try {
            ensureMessagesConfig(configPath)
            Messages.load(apiMessagesFile)
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to load MiniGamesAPI messages.yml: ${t.message}")
            plugin.logger.severe(t.stackTraceToString())
        }

        // Ensure lobby items config is in memory before players click.
        LobbyItemsManager.reloadFromConfig()

        plugin.logger.info("MiniGamesAPI config loaded successfully.")
    }

    private fun migrateApiConfigV2(configPath: Path) {
        try {
            val file = configPath.toFile()
            if (!file.exists()) return
            val yaml = YamlConfiguration.loadConfiguration(file)

            val version = yaml.getInt("minigamesapi.config_version", 1)

            // mode.name priority, fallback to legacy spartakiad.minigame_name
            val legacySpartaName = yaml.getString("spartakiad.minigame_name")?.trim()?.takeIf { it.isNotEmpty() }
            val modeName = yaml.getString("mode.name")?.trim()?.takeIf { it.isNotEmpty() }

            if (modeName == null && legacySpartaName != null) {
                yaml.set("mode.name", legacySpartaName)
            }

            // Always remove legacy key to avoid divergence.
            if (yaml.contains("spartakiad.minigame_name")) {
                yaml.set("spartakiad.minigame_name", null)
            }

            val finalModeName = (yaml.getString("mode.name") ?: "minigame").trim().ifEmpty { "minigame" }

            // Some configs may use different naming conventions for display name.
            // Prefer "mode.display_name" but also accept legacy variants like "mode.display-name" and "mode.displayName".
            val displayUnderscore = yaml.getString("mode.display_name")?.trim()?.takeIf { it.isNotEmpty() }
            val displayDash = yaml.getString("mode.display-name")?.trim()?.takeIf { it.isNotEmpty() }
            val displayCamel = yaml.getString("mode.displayName")?.trim()?.takeIf { it.isNotEmpty() }

            if (displayUnderscore == null) {
                val imported = displayDash ?: displayCamel
                if (!imported.isNullOrBlank()) {
                    yaml.set("mode.display_name", imported)
                }
            }

            // Remove legacy keys to avoid divergence.
            if (yaml.contains("mode.display-name")) {
                yaml.set("mode.display-name", null)
            }
            if (yaml.contains("mode.displayName")) {
                yaml.set("mode.displayName", null)
            }

            val finalDisplay = yaml.getString("mode.display_name")?.trim().orEmpty()
            if (finalDisplay.isEmpty()) {
                yaml.set("mode.display_name", finalModeName.uppercase())
            }

            // Ensure tournament team cap key exists (used for strict prelogin team limit).
            if (!yaml.contains("tournament.max_online_per_team")) {
                yaml.set("tournament.max_online_per_team", 4)
            }

            if (version < 2) {
                yaml.set("minigamesapi.config_version", 2)
            }

            yaml.save(file)
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to migrate MiniGamesAPI config to v2: ${t.message}")
        }
    }


    private fun migrateApiConfigV3(configPath: Path) {
        try {
            val file = configPath.toFile()
            if (!file.exists()) return
            val yaml = YamlConfiguration.loadConfiguration(file)

            val version = yaml.getInt("minigamesapi.config_version", 1)

            if (!yaml.contains("tournament.mode")) {
                yaml.set("tournament.mode", "standard")
            }

            if (!yaml.contains("tournament.elo.min_attempts")) {
                yaml.set("tournament.elo.min_attempts", 3)
            }

            if (!yaml.contains("tournament.elo.auto_recalc")) {
                yaml.set("tournament.elo.auto_recalc", true)
            }

            if (!yaml.contains("tournament.elo.announce_after_match")) {
                yaml.set("tournament.elo.announce_after_match", true)
            }

            if (!yaml.contains("tournament.elo.post_match.kick_participants")) {
                yaml.set("tournament.elo.post_match.kick_participants", false)
            }

            if (!yaml.contains("tournament.elo.post_match.kick_delay_ticks")) {
                yaml.set("tournament.elo.post_match.kick_delay_ticks", 0)
            }

            // Add missing post-match keys so admins can see/tune them.
            if (!yaml.contains("tournament.post_match.enforce_delay_ticks")) {
                yaml.set("tournament.post_match.enforce_delay_ticks", 40)
            }
            if (!yaml.contains("tournament.post_match.kick_participants")) {
                yaml.set("tournament.post_match.kick_participants", true)
            }
            if (!yaml.contains("tournament.post_match.kick_delay_ticks")) {
                yaml.set("tournament.post_match.kick_delay_ticks", 0)
            }

            if (version < 3) {
                yaml.set("minigamesapi.config_version", 3)
            }

            yaml.save(file)
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to migrate MiniGamesAPI config to v3: ${t.message}")
        }
    }

    private fun ensureMessagesConfig(configPath: Path) {
        val messagesPath = apiDataPath.resolve("messages.yml")

        if (!messagesPath.exists()) {
            plugin.logger.info("MiniGamesAPI messages.yml not found at $messagesPath, creating default...")
            saveClasspathResourceOrDefault("minigamesapi/messages.yml", messagesPath)

            // Best-effort: import old message texts from config.yml if they existed there.
            try {
                val cfg = YamlConfiguration.loadConfiguration(configPath.toFile())
                val msg = YamlConfiguration.loadConfiguration(messagesPath.toFile())

                // Matchmaking announce messages
                if (cfg.contains("matchmaking.start.announce.message") && !msg.contains("messages.matchmaking.start.announce.message")) {
                    msg.set("messages.matchmaking.start.announce.message", cfg.getString("matchmaking.start.announce.message"))
                }
                if (cfg.contains("matchmaking.start.announce.cancelled_message") && !msg.contains("messages.matchmaking.start.announce.cancelled_message")) {
                    msg.set(
                        "messages.matchmaking.start.announce.cancelled_message",
                        cfg.getString("matchmaking.start.announce.cancelled_message")
                    )
                }
                if (cfg.contains("matchmaking.start.announce.ready_message") && !msg.contains("messages.matchmaking.start.announce.ready_message")) {
                    msg.set("messages.matchmaking.start.announce.ready_message", cfg.getString("matchmaking.start.announce.ready_message"))
                }

                // Teamselect GUI title + team names
                if (cfg.contains("teamselect.title") && !msg.contains("ui.teamselect.title")) {
                    msg.set("ui.teamselect.title", cfg.getString("teamselect.title"))
                }
                for (i in 1..16) {
                    val p = "teamselect.teams.$i.name"
                    val np = "ui.teamselect.teams.$i.name"
                    if (cfg.contains(p) && !msg.contains(np)) {
                        msg.set(np, cfg.getString(p))
                    }
                }

                // Lobby hotbar item names/lore/deny
                val hotbar = cfg.getMapList("lobby.items.hotbar")
                for (raw in hotbar) {
                    val id = (raw["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val base = "ui.lobby.items.$id"

                    if (!msg.contains("$base.name") && raw["name"] is String) {
                        msg.set("$base.name", raw["name"])
                    }
                    if (!msg.contains("$base.lore") && raw["lore"] is List<*>) {
                        msg.set("$base.lore", raw["lore"])
                    }

                    val action = raw["action"] as? Map<*, *> ?: continue
                    val deny = action["deny_message"] as? String
                    if (deny != null && !msg.contains("$base.deny_message")) {
                        msg.set("$base.deny_message", deny)
                    }
                }

                msg.save(messagesPath.toFile())
            } catch (_: Throwable) {
            }
        }

        // Soft migration for messages.yml: add missing keys from defaults, without overwriting existing.
        try {
            val msgFile = messagesPath.toFile()
            if (msgFile.exists()) {
                val msg = YamlConfiguration.loadConfiguration(msgFile)
                val defaults = YamlConfiguration()
                defaults.loadFromString(DEFAULT_MESSAGES_CONFIG)

                var changed = false
                for (k in defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(k)) continue
                    if (!msg.contains(k)) {
                        msg.set(k, defaults.get(k))
                        changed = true
                    }
                }

                if (changed) {
                    msg.save(msgFile)
                }
            }
        } catch (_: Throwable) {
        }
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
                    yaml.contains("tournament")

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
            "minigamesapi/messages.yml" -> DEFAULT_MESSAGES_CONFIG
            "minigamesapi/tournament_qualifier.yml" -> DEFAULT_TOURNAMENT_QUALIFIER_CONFIG
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

    private fun initTournamentManager() {
        try {
            TournamentManager.initialize(
                plugin = plugin,
                configuration = configuration,
                resultsFile = apiResultsFile,
            )
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to initialize TournamentManager: ${t.message}")
            plugin.logger.severe(t.stackTraceToString())
        }
    }

    private fun initTournamentQualifierManager() {
        try {
            if (!apiTournamentQualifierFile.exists()) {
                saveClasspathResourceOrDefault(
                    "minigamesapi/tournament_qualifier.yml",
                    apiTournamentQualifierFile.toPath(),
                )
            }

            TournamentQualifierManager.initialize(
                plugin = plugin,
                file = apiTournamentQualifierFile,
            )
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to initialize TournamentQualifierManager: ${t.message}")
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
        } catch (t: Throwable) {
            plugin.logger.severe("Failed to load LibreLogin dynamically: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            libreLogin = null
        }
    }

    private fun tryCreateLibreLoginUuidResolver(libreLoginInstance: Any): UuidResolver? {
        return try {
            val libreLoginPluginClass = Class.forName("xyz.kyngs.librelogin.api.LibreLoginPlugin")
            val ctor = LibreLoginUuidResolver::class.java.getConstructor(libreLoginPluginClass)
            ctor.newInstance(libreLoginInstance) as UuidResolver
        } catch (t: Throwable) {
            plugin.logger.warning(
                "Failed to create LibreLoginUuidResolver, falling back to Bukkit UUIDs: ${t.javaClass.simpleName}: ${t.message}"
            )
            plugin.logger.fine(t.stackTraceToString())
            null
        }
    }

    private fun registerEvents() {
        Bukkit.getPluginManager().registerEvents(AsyncPlayerPreLoginListener, plugin)

        Bukkit.getPluginManager().registerEvents(LobbyItemsListener, plugin)
        Bukkit.getPluginManager().registerEvents(TeamSelectionGui, plugin)

        Bukkit.getPluginManager().registerEvents(PlayerJoinListener, plugin)
        Bukkit.getPluginManager().registerEvents(PlayerQuitListener, plugin)

    }

    private fun registerCommands() {
        val readyCmd = ReadyCommand.getBuilder().build()
        val teamSelectCmd = TeamSelectCommand.getBuilder().build()
        val unreadyCmd = UnreadyCommand.getBuilder().build()
        val mgCmd = MiniGamesCommand.getBuilder().then(StartCommand.getBuilder()).build()
        val forceRunCmd = ForceRunCommand.getBuilder().build()
        val forceReadyCmd = ForceReadyCommand.getBuilder().build()
        val tournamentCmd = TournamentCommand.getBuilder().build()

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(readyCmd)
            event.registrar().register(teamSelectCmd)
            event.registrar().register(unreadyCmd)
            event.registrar().register(mgCmd)
            event.registrar().register(forceRunCmd)
            event.registrar().register(forceReadyCmd)
            event.registrar().register(tournamentCmd)
        }
    }

    fun shutdown() {
        plugin.logger.info("MiniGamesCore.shutdown()")

        LobbyScoreboardManager.stop()

        try {
            TournamentManager.shutdown()
        } catch (_: Throwable) {
        }

        try {
            configuration.close()
        } catch (_: Throwable) {
        }

        ResultsManager.shutdown()
    }

    private const val DEFAULT_API_CONFIG: String = """
minigamesapi:
  # Service marker to distinguish API config from mode configs.
  config_version: 3

mode:
  # Short logical key used for DB (mode_key) and internal identifiers.
  name: "minigame"
  # Human readable name used for prefixes / UI.
  display_name: "MINIGAME"

uuid:
  # Use LibreLogin UUID resolver via reflection (fallback to Bukkit UUID if LibreLogin is missing).
  use_libre_login: true

storage:
  debounce_millis: 500
  close_timeout_millis: 5000


tournament:
  enabled: false
  # standard: "до победного" (attempts/win); elo: бесконечный режим под Elo квалификацию
  mode: "standard"
  elo:
    min_attempts: 3
    auto_recalc: true
    post_match:
      kick_participants: false
      kick_delay_ticks: 0
  max_online_per_team: 4
  event_id: "tournament"
  stage: "round1"
  previous_stage: ""
  default_attempts: 1
  prelogin:
    strict: true
  post_match:
    enforce_delay_ticks: 40
    kick_participants: true
    kick_delay_ticks: 0
  bypass:
    permission: "minigamesapi.tournament.bypass"
    uuids: []


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

lobby:
  items:
    enabled: true
    hotbar:
      - id: quick_ready
        enabled: true
        slot: 2
        material: EMERALD
        action:
          type: READY
      - id: team_select
        enabled: true
        slot: 4
        material: NETHER_STAR
        action:
          type: TEAM_SELECT
      - id: lobby_return
        enabled: true
        slot: 6
        material: COMPASS
        action:
          type: COMMAND
          command: "lobby"

teamselect:
  teams:
    "1": { material: RED_WOOL, color: RED }
    "2": { material: YELLOW_WOOL, color: YELLOW }
    "3": { material: GREEN_WOOL, color: GREEN }
    "4": { material: BLUE_WOOL, color: BLUE }
    "5": { material: CYAN_WOOL, color: AQUA }
    "6": { material: PURPLE_WOOL, color: LIGHT_PURPLE }
    "7": { material: ORANGE_WOOL, color: GOLD }
    "8": { material: LIGHT_BLUE_WOOL, color: DARK_AQUA }
    "9": { material: WHITE_WOOL, color: WHITE }
    "10": { material: BLACK_WOOL, color: BLACK }
    "11": { material: MAGENTA_WOOL, color: DARK_PURPLE }
    "12": { material: LIME_WOOL, color: DARK_GREEN }
    "13": { material: PINK_WOOL, color: LIGHT_PURPLE }
    "14": { material: BROWN_WOOL, color: DARK_RED }
    "15": { material: GRAY_WOOL, color: GRAY }
    "16": { material: LIGHT_GRAY_WOOL, color: DARK_GRAY }
"""

    private const val DEFAULT_MESSAGES_CONFIG: String = """
messages:
  # Prefix for all API technical messages. Placeholders: {mode_display}
  prefix: "&7[&b{mode_display}&7] "

  common:
    only_players: "&cЭта команда доступна только игрокам."

  join:
    help: "&7Команды: &a/ready&7, &b/teamselect&7, &c/unready"
    help_tournament: "&7Турнир: команды назначаются организаторами. Команды: &e/forceready&7, &c/unready&7, &e/lobby"
    help_tournament_elo: "&7Квалификация (ELO): команды назначаются организаторами. Матчи собираются автоматически, попытки не ограничены (минимум 3). Команды: &e/forceready&7, &c/unready&7, &e/lobby"

  feedback:
    label: "&bФорма обратной связи"
    url: ""
    hover: "&7Нажми, чтобы открыть"
    missing: "&7Форма обратной связи: &c(не настроено)"

  lobby:
    command_unavailable: "&cКоманда недоступна."

  tournament:
    not_participant: "&cВы не участник турнира."
    no_attempts: "&cУ вашей команды закончились попытки на этот этап."
    winner: "&cВаша команда уже прошла этот этап."
    not_qualified: "&cВаша команда не прошла предыдущий этап."
    team_full_online: "&cНа этом сервере уже максимум игроков из вашей команды. Подождите, пока кто-то выйдет."
    disabled_teamselect: "&cВ турнире команды назначаются организаторами."
    # /ready should NOT change anything in tournament mode. Use /forceready to confirm incomplete roster.
    ready_confirm: "&eВы уверены?! &7Если хотите стартовать неполным составом, используйте &a/forceready&7."
    forceready_only_in_tournament: "&cКоманда доступна только в режиме турнира."
    only_captain: "&cГотовность неполным составом может подтвердить только капитан (если он онлайн)."
    force_ready_on: "&aОк! Команда помечена как готовая к старту неполным составом."
    force_ready_off: "&eОк! Метка готовности неполным составом снята."
    force_ready_cleared: "&eМетка готовности неполным составом снята."
    force_ready_already_cleared: "&7Метка готовности неполным составом уже снята."
    moved_to_ceremony: "&7Вы больше не можете участвовать в этом этапе. Переносим на торжественную карту..."
    moved_to_ceremony_kick_later: "&7Вы больше не можете участвовать в этом этапе. Через &f{seconds}&7 сек вы будете отключены."
    post_match_kick: "&7Матч завершён. Для следующей попытки зайдите на сервер снова."
    # Elo-режим: обновление рейтинга после матча (placeholders: {team_key}, {rating}, {delta}, {place}, {paint_percent})
    elo_rating_update: "&bELO&7: &f{team_key}&7 = &a{rating}&7 (&e{delta}&7) &8| &7место: &f{place}&7, краска: &f{paint_percent}&7%"
    elo_rating_update_skipped: "&cELO не учтён: &7{reason}"
    error: "&cОшибка проверки доступа. Обратитесь к администратору."

  ready:
    in_game: "&cВы уже в игре."
    already_in_queue: "&eВы уже в очереди."
    no_free_arenas: "&cНет свободных арен."
    no_free_slots: "&cНет свободных слотов."
    failed: "&cНе удалось добавить вас в очередь."
    added: "&aВы добавлены в очередь. Команда: &f{team}"

  unready:
    removed: "&aВы покинули очередь."
    not_in_queue: "&eВы не в очереди."

  teamselect:
    in_game: "&cВы уже в игре."
    no_free_arenas: "&cНет свободных арен."
    choose_team: "&eВыберите команду."
    match_already_started: "&cМатч уже начался."
    team_full: "&cКоманда заполнена."
    added: "&aВы выбрали команду &f{team}&a."

  forcerun:
    no_instances: "&cНет активных инстансов."
    will_start: "&aИнстанс поставлен на старт."

  matchmaking:
    start:
      announce:
        # Placeholders: {seconds}, {current}, {max}, {required}, {teams_current}, {teams_required}
        message: "&eДо начала игры &6{seconds}&e сек. Если не наберётся полный матч (&6{current}&e/&6{max}&e, команд &6{teams_current}&e/&6{teams_required}&e) — стартуем."
        cancelled_message: "&cСтарт отменён: недостаточно игроков или команд."
        ready_message: "&aМатч стартует неполным составом (&f{current}&a/&f{max}&a, команд &f{teams_current}&a/&f{teams_required}&a)."

ui:
  teamselect:
    title: "&8Выбор команды"
    teams:
      "1": { name: "&cКоманда 1" }
      "2": { name: "&eКоманда 2" }
      "3": { name: "&aКоманда 3" }
      "4": { name: "&9Команда 4" }
      "5": { name: "&bКоманда 5" }
      "6": { name: "&dКоманда 6" }
      "7": { name: "&6Команда 7" }
      "8": { name: "&3Команда 8" }
      "9": { name: "&fКоманда 9" }
      "10": { name: "&0Команда 10" }
      "11": { name: "&5Команда 11" }
      "12": { name: "&2Команда 12" }
      "13": { name: "&dКоманда 13" }
      "14": { name: "&4Команда 14" }
      "15": { name: "&7Команда 15" }
      "16": { name: "&8Команда 16" }

  lobby:
    items:
      quick_ready:
        name: "&aГотов"
        lore:
          - "&7Быстро встать в очередь"
      team_select:
        name: "&bВыбор команды"
        lore:
          - "&7Открыть меню выбора команды"
      lobby_return:
        name: "&eВ лобби"
        lore:
          - "&7Вернуться в лобби"
        deny_message: "&cКоманда недоступна.""""

    private const val DEFAULT_TOURNAMENT_QUALIFIER_CONFIG: String = """
event_id: "spartakiad_2026"
stage: "qualifier_splatoon"

min_matches: 3

elo:
  start_rating: 1000
  k_placement:
    provisional_matches: 10
    k_provisional: 24
    k_stable: 16
  scale: 400

data:
  paint_percent_key: "paint_percent"
  allow_fallback_to_score: false
  paint_percent_format: "0_100" # 0_100 | 0_1

locking:
  mode: "timestamp"   # timestamp | match_id
  locked: false
  locked_at: 0
  locked_match_id: ""

advance:
  default_thresholds:
    - { min_teams: 16, take: 16 }
    - { min_teams: 8,  take: 8  }
    - { min_teams: 0,  take: 0  } # 0 = take all
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
