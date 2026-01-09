package ru.joutak.minigames.config

object ConfigKeys {
    private val configKeys = mutableSetOf<ConfigKey<*>>()

    // === Spartakiad ===
    val SPARTAKIAD_ENABLED = object : ConfigKey<Boolean>("spartakiad.enabled", false) {}
    val SPARTAKIAD_MINIGAME_NAME = object : ConfigKey<String>("spartakiad.minigame_name", "minigame".lowercase()) {}
    val SPARTAKIAD_ATTEMPTS = object : ConfigKey<Int>("spartakiad.attempts", 5) {}
    val SPARTAKIAD_TEAM_MODE = object : ConfigKey<Boolean>("spartakiad.team_mode", false) {}

    // === UUID ===
    val USE_LIBRE_LOGIN = object : ConfigKey<Boolean>("uuid.use_libre_login", true) {}

    // === Storage ===
    val STORAGE_DEBOUNCE_MILLIS = object : ConfigKey<Long>("storage.debounce_millis", 500) {}
    val STORAGE_CLOSE_TIMEOUT_MILLIS = object : ConfigKey<Long>("storage.close_timeout_millis", 5000) {}

    // === Lobby items ===
    private val DEFAULT_LOBBY_HOTBAR: List<Map<String, Any>> =
        listOf(
            mapOf(
                "id" to "quick_ready",
                "enabled" to true,
                "slot" to 0,
                "material" to "EMERALD",
                "name" to "&aГотов",
                "lore" to listOf("&7Быстро встать в очередь"),
                "action" to mapOf("type" to "READY"),
            ),
            mapOf(
                "id" to "team_select",
                "enabled" to true,
                "slot" to 1,
                "material" to "NETHER_STAR",
                "name" to "&bВыбор команды",
                "lore" to listOf("&7Открыть меню выбора команды"),
                "action" to mapOf("type" to "TEAM_SELECT"),
            ),
            mapOf(
                "id" to "lobby_return",
                "enabled" to true,
                "slot" to 8,
                "material" to "RED_BED",
                "name" to "&eВ лобби",
                "lore" to listOf("&7Телепорт в лобби"),
                "action" to mapOf(
                    "type" to "COMMAND",
                    "command" to "lobby",
                    "deny_message" to "&cКоманда /lobby недоступна.",
                ),
            ),
        )

    val LOBBY_ITEMS_ENABLED = object : ConfigKey<Boolean>("lobby.items.enabled", true) {}
    val LOBBY_HOTBAR_ITEMS =
        object : ConfigKey<List<Map<String, Any>>>("lobby.items.hotbar", DEFAULT_LOBBY_HOTBAR) {}

    // === Team select GUI ===
    val TEAMSELECT_TITLE = object : ConfigKey<String>("teamselect.title", "&8Выбор команды") {}

    private val DEFAULT_TEAMSELECT_TEAMS: Map<String, Any> =
        mapOf(
            "1" to mapOf("material" to "RED_WOOL", "color" to "RED"),
            "2" to mapOf("material" to "YELLOW_WOOL", "color" to "YELLOW"),
            "3" to mapOf("material" to "GREEN_WOOL", "color" to "GREEN"),
            "4" to mapOf("material" to "BLUE_WOOL", "color" to "BLUE"),
            "5" to mapOf("material" to "PURPLE_WOOL", "color" to "DARK_PURPLE"),
            "6" to mapOf("material" to "ORANGE_WOOL", "color" to "GOLD"),
            "7" to mapOf("material" to "PINK_WOOL", "color" to "LIGHT_PURPLE"),
            "8" to mapOf("material" to "CYAN_WOOL", "color" to "AQUA"),
            "9" to mapOf("material" to "LIME_WOOL", "color" to "GREEN"),
            "10" to mapOf("material" to "LIGHT_BLUE_WOOL", "color" to "AQUA"),
            "11" to mapOf("material" to "MAGENTA_WOOL", "color" to "LIGHT_PURPLE"),
            "12" to mapOf("material" to "BROWN_WOOL", "color" to "DARK_RED"),
            "13" to mapOf("material" to "BLACK_WOOL", "color" to "DARK_GRAY"),
            "14" to mapOf("material" to "WHITE_WOOL", "color" to "WHITE"),
            "15" to mapOf("material" to "GRAY_WOOL", "color" to "GRAY"),
            "16" to mapOf("material" to "LIGHT_GRAY_WOOL", "color" to "GRAY"),
        )

    val TEAMSELECT_TEAMS = object : ConfigKey<Map<String, Any>>("teamselect.teams", DEFAULT_TEAMSELECT_TEAMS) {}

    fun register(key: ConfigKey<*>) {
        configKeys += key
    }

    fun getAll(): Set<ConfigKey<*>> = configKeys
}