package ru.joutak.minigames.config

object ConfigKeys {
    private val configKeys = mutableSetOf<ConfigKey<*>>()

    /**
     * Logical mode name (used as mode_key for results database and other cross-mode integrations).
     * Example: "splatoon", "creakywars".
     */
    val MODE_NAME = object : ConfigKey<String>("mode.name", "minigame") {}

    /**
     * Human-readable mode name for prefixes / UI.
     * Example: "СПЛАТУН", "CREAKYWARS".
     */
    val MODE_DISPLAY_NAME = object : ConfigKey<String>("mode.display_name", "MINIGAME") {}

    val SPARTAKIAD_ENABLED = object : ConfigKey<Boolean>("spartakiad.enabled", false) {}
    val SPARTAKIAD_MINIGAME_NAME = object : ConfigKey<String>("spartakiad.minigame_name", "minigame".lowercase()) {}
    val SPARTAKIAD_ATTEMPTS = object : ConfigKey<Int>("spartakiad.attempts", 5) {}
    val SPARTAKIAD_TEAM_MODE = object : ConfigKey<Boolean>("spartakiad.team_mode", false) {}

    val USE_LIBRE_LOGIN = object : ConfigKey<Boolean>("uuid.use_libre_login", true) {}

    val STORAGE_DEBOUNCE_MILLIS = object : ConfigKey<Long>("storage.debounce_millis", 500) {}
    val STORAGE_CLOSE_TIMEOUT_MILLIS = object : ConfigKey<Long>("storage.close_timeout_millis", 5000) {}

    /**
     * Allows starting a match when the instance isn't full, but has enough players/teams.
     * This is useful for modes like BedWars/CreakyWars.
     */
    val MATCHMAKING_START_ENABLED = object : ConfigKey<Boolean>("matchmaking.start.enabled", false) {}

    /**
     * How many identical lobbies/instances to keep available per configured arena.
     *
     * This is a server-side "pool size" multiplier: if your mode loads 1 instance config for map "game",
     * and this value is 3, matchmaking will allow up to 3 parallel matches based on the same map config.
     *
     * Default: 1 (old behavior).
     */
    val MATCHMAKING_INSTANCE_POOL_SIZE = object : ConfigKey<Int>("matchmaking.instance_pool_size", 1) {
        override fun validate(value: Int) {
            require(value >= 1) { "matchmaking.instance_pool_size must be >= 1" }
        }
    }

    /**
     * Minimal fraction (0.0..1.0) of instance capacity to allow a start.
     * Example: 0.5 means "start when at least half of slots are occupied".
     * IMPORTANT: full instance always starts regardless of this setting.
     */
    val MATCHMAKING_START_MIN_FILL_PERCENT = object : ConfigKey<Double>("matchmaking.start.min_fill_percent", 1.0) {
        override fun validate(value: Double) {
            require(value in 0.0..1.0) { "matchmaking.start.min_fill_percent must be in [0.0, 1.0]" }
        }
    }

    /**
     * Minimal number of non-empty teams required to allow a start.
     * Example: 2 means "at least two teams must have at least one player".
     */
    val MATCHMAKING_START_MIN_TEAMS = object : ConfigKey<Int>("matchmaking.start.min_teams", 2) {
        override fun validate(value: Int) {
            require(value >= 1) { "matchmaking.start.min_teams must be >= 1" }
        }
    }

    /**
     * Optional warmup delay (in seconds) once the threshold is reached.
     * If players drop below the threshold during the delay, the start is cancelled.
     */
    val MATCHMAKING_START_DELAY_SECONDS = object : ConfigKey<Int>("matchmaking.start.delay_seconds", 0) {
        override fun validate(value: Int) {
            require(value >= 0) { "matchmaking.start.delay_seconds must be >= 0" }
        }
    }

    /**
     * Sends chat announcements for partial-start countdown ("game will start even if not full").
     */
    val MATCHMAKING_START_ANNOUNCE_ENABLED = object : ConfigKey<Boolean>("matchmaking.start.announce.enabled", true) {}

    /**
     * How often to announce countdown in chat (seconds). Example: 5 -> every 5 seconds.
     * The last [MATCHMAKING_START_ANNOUNCE_LAST_SECONDS_ALWAYS] seconds are announced every second.
     */
    val MATCHMAKING_START_ANNOUNCE_INTERVAL_SECONDS =
        object : ConfigKey<Int>("matchmaking.start.announce.interval_seconds", 5) {
            override fun validate(value: Int) {
                require(value >= 1) { "matchmaking.start.announce.interval_seconds must be >= 1" }
            }
        }

    /**
     * During the last N seconds of countdown, announcements are sent every second.
     */
    val MATCHMAKING_START_ANNOUNCE_LAST_SECONDS_ALWAYS =
        object : ConfigKey<Int>("matchmaking.start.announce.last_seconds_always", 5) {
            override fun validate(value: Int) {
                require(value >= 0) { "matchmaking.start.announce.last_seconds_always must be >= 0" }
            }
        }

    /**
     * Countdown message template. Supports placeholders:
     * {seconds}, {current}, {max}, {required}, {teams_current}, {teams_required}
     */
    val MATCHMAKING_START_ANNOUNCE_MESSAGE =
        object : ConfigKey<String>(
            "matchmaking.start.announce.message",
            "&eДо начала игры &6{seconds}&e сек. Если не наберётся полный матч (&6{current}&e/&6{max}&e, команд &6{teams_current}&e/&6{teams_required}&e) — стартуем."
        ) {}

    /**
     * Message when countdown is cancelled (players/teams dropped below threshold).
     */
    val MATCHMAKING_START_ANNOUNCE_CANCELLED_MESSAGE =
        object : ConfigKey<String>(
            "matchmaking.start.announce.cancelled_message",
            "&cСтарт отменён: недостаточно игроков или команд."
        ) {}

    /**
     * Message when instance becomes ready via partial-start (not full, but threshold reached).
     */
    val MATCHMAKING_START_ANNOUNCE_READY_MESSAGE =
        object : ConfigKey<String>(
            "matchmaking.start.announce.ready_message",
            "&aМатч стартует неполным составом (&f{current}&a/&f{max}&a, команд &f{teams_current}&a/&f{teams_required}&a)."
        ) {}

    fun register(key: ConfigKey<*>) {
        configKeys += key
    }

    fun getAll(): Set<ConfigKey<*>> = configKeys
}
