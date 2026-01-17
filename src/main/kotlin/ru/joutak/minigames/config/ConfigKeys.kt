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

    /**
     * Tournament mode (new name for legacy "spartakiad" mode).
     * When enabled, access/queue/team selection is controlled by TournamentManager.
     */
    val TOURNAMENT_ENABLED = object : ConfigKey<Boolean>("tournament.enabled", false) {}

    /**
     * Tournament id (shared across all modes/stages).
     * Example: "spartakiad_2026".
     */
    val TOURNAMENT_EVENT_ID = object : ConfigKey<String>("tournament.event_id", "spartakiad") {}

    /**
     * Current tournament stage on this server.
     * Example: "round1", "round2", "round3".
     */
    val TOURNAMENT_STAGE = object : ConfigKey<String>("tournament.stage", "round1") {}

    /**
     * If set, only teams who WON the previous stage can participate in the current stage.
     * Example: current "round2" requires previous "round1".
     */
    val TOURNAMENT_PREVIOUS_STAGE = object : ConfigKey<String>("tournament.previous_stage", "") {}

    /**
     * Default attempts for a team per stage (created on first access if absent in DB).
     */
    val TOURNAMENT_DEFAULT_ATTEMPTS = object : ConfigKey<Int>("tournament.default_attempts", 1) {
        override fun validate(value: Int) {
            require(value >= 0) { "tournament.default_attempts must be >= 0" }
        }
    }

    /**
     * Max online players allowed from the same tournament team_key at once.
     * Prevents overfilling a team when tournament matches are strictly 4v4v4v4.
     */
    val TOURNAMENT_MAX_ONLINE_PER_TEAM = object : ConfigKey<Int>("tournament.max_online_per_team", 4) {
        override fun validate(value: Int) {
            require(value >= 1) { "tournament.max_online_per_team must be >= 1" }
        }
    }

    /**
     * Permission for admin bypass in tournament mode.
     * IMPORTANT: PreLoginEvent does not have permissions, so uuid list is also supported.
     */
    val TOURNAMENT_BYPASS_PERMISSION = object : ConfigKey<String>("tournament.bypass.permission", "minigamesapi.tournament.bypass") {}

    /**
     * Additional bypass list for AsyncPlayerPreLoginEvent (no permissions there).
     * Values are UUID strings.
     */
    val TOURNAMENT_BYPASS_UUIDS = object : ConfigKey<List<String>>("tournament.bypass.uuids", emptyList()) {}

    /**
     * If true, tournament gate is applied on pre-login strictly.
     * If false, gate may be applied later on join (useful when name->uuid binding is needed).
     */
    val TOURNAMENT_PRELOGIN_STRICT = object : ConfigKey<Boolean>("tournament.prelogin.strict", true) {}

    /**
     * Delay (in ticks) before enforcing ineligible teams after a match is recorded.
     * Helps modes finish their own ENDING/CLEANUP before players are moved or kicked.
     */
    val TOURNAMENT_POST_MATCH_ENFORCE_DELAY_TICKS = object : ConfigKey<Int>("tournament.post_match.enforce_delay_ticks", 40) {
        override fun validate(value: Int) {
            require(value >= 0) { "tournament.post_match.enforce_delay_ticks must be >= 0" }
        }
    }

    /**
     * Optional ceremony map for teams that ran out of attempts or already won.
     * If enabled, players are moved there instead of being kicked.
     */
    val TOURNAMENT_CEREMONY_ENABLED = object : ConfigKey<Boolean>("tournament.ceremony.enabled", false) {}

    val TOURNAMENT_CEREMONY_WORLD = object : ConfigKey<String>("tournament.ceremony.world", "tourney_ceremony") {}

    /**
     * Seat locations in ceremony world: strings like "x y z yaw pitch" (yaw/pitch optional).
     */
    val TOURNAMENT_CEREMONY_SEATS = object : ConfigKey<List<String>>("tournament.ceremony.seats", emptyList()) {}

    /**
     * Fallback location in ceremony world when no seats are configured or parsing failed.
     */
    val TOURNAMENT_CEREMONY_FALLBACK = object : ConfigKey<String>("tournament.ceremony.fallback", "0 64 0 0 0") {}

    /**
     * If > 0, players moved to ceremony are kicked after this many seconds. 0 = do not kick.
     */
    val TOURNAMENT_CEREMONY_KICK_AFTER_SECONDS = object : ConfigKey<Int>("tournament.ceremony.kick_after_seconds", 0) {
        override fun validate(value: Int) {
            require(value >= 0) { "tournament.ceremony.kick_after_seconds must be >= 0" }
        }
    }


    /**
     * Global ceremony map (applies to ANY match end, both matchmaking and tournament).
     *
     * If enabled, participants of a recorded match are moved to a ceremony world clone and
     * restricted to their team pedestal square.
     */
    val CEREMONY_ENABLED = object : ConfigKey<Boolean>("ceremony.enabled", false) {}

    /**
     * Template world name to clone for ceremony.
     *
     * If empty, falls back to tournament.ceremony.world (legacy).
     */
    val CEREMONY_TEMPLATE_WORLD = object : ConfigKey<String>("ceremony.template_world", "") {}

    /**
     * Optional explicit clone world name. If empty, a stable per-server name is generated.
     */
    val CEREMONY_CLONE_WORLD = object : ConfigKey<String>("ceremony.clone_world", "") {}

    /**
     * Delay (ticks) before moving match participants to ceremony (to let modes finish ENDING/CLEANUP).
     */
    val CEREMONY_POST_MATCH_DELAY_TICKS = object : ConfigKey<Int>("ceremony.post_match_delay_ticks", 40) {
        override fun validate(value: Int) {
            require(value >= 0) { "ceremony.post_match_delay_ticks must be >= 0" }
        }
    }

    /**
     * 4 pedestal regions (one per team) in the ceremony world.
     *
     * Format per entry (integers, commas/spaces accepted):
     *  - "x z" (2 numbers) -> 2x2 at spawnY
     *  - "x y z" (3 numbers) -> 2x2 at given y
     *  - "x1 z1 x2 z2" (4 numbers) -> rectangle at spawnY
     *  - "x1 y z1 x2 z2" (5 numbers) -> rectangle at y
     *  - "x1 y z1 x2 y2 z2" (6+ numbers) -> rectangle at y
     */
    val CEREMONY_PEDESTALS = object : ConfigKey<List<String>>("ceremony.pedestals", emptyList()) {}

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
