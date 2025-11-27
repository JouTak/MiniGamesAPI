package ru.joutak.minigames.spartakiad.playerData.storage

import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.PlayerData
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SqlitePlayerDataStorage(
    private val databaseFile: File,
) : PlayerDataStorage {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "playerdata-sqlite-${databaseFile.name}").apply { isDaemon = true }
        }

    private val connection: Connection

    init {
        databaseFile.parentFile?.mkdirs()

        val url = "jdbc:sqlite:${databaseFile.absolutePath}"
        Class.forName("org.sqlite.JDBC")
        connection =
            DriverManager.getConnection(url).apply {
                autoCommit = true
                createStatement().use { st ->
                    st.execute("PRAGMA journal_mode = WAL;")
                    st.execute("PRAGMA synchronous = NORMAL;")
                    st.execute("PRAGMA foreign_keys = ON;")
                    st.execute("PRAGMA busy_timeout = 5000;")
                }
            }

        createTableIfNeeded()
    }

    private fun createTableIfNeeded() {
        val sql =
            """
            CREATE TABLE IF NOT EXISTS player_data(
              uuid TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              won INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        connection.createStatement().use { it.execute(sql) }
        ensureWonColumnExists()
    }

    private fun ensureWonColumnExists() {
        val infoSql = "PRAGMA table_info(player_data);"
        var hasWon = false
        connection.createStatement().use { st ->
            st.executeQuery(infoSql).use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("name")
                    if (colName.equals("won", ignoreCase = true)) {
                        hasWon = true
                        break
                    }
                }
            }
        }
        if (!hasWon) {
            connection.createStatement().use { st ->
                st.execute("ALTER TABLE player_data ADD COLUMN won INTEGER NOT NULL DEFAULT 0;")
            }
        }
    }

    // Async methods

    override fun getPlayerData(uuid: UUID): CompletableFuture<PlayerData?> =
        CompletableFuture.supplyAsync({
            getPlayerDataSync(uuid)
        }, executor)

    override fun createIfNotExists(
        uuid: UUID,
        name: String,
        initialAttempts: Int,
    ): CompletableFuture<PlayerData> =
        CompletableFuture.supplyAsync({ createIfNotExistsSync(uuid, name, initialAttempts) }, executor)

    override fun upsertPlayerData(playerData: PlayerData): CompletableFuture<Unit> =
        CompletableFuture.runAsync({ upsertPlayerDataSync(playerData) }, executor).thenApply {}

    override fun updateAttempts(
        uuid: UUID,
        attempts: Int,
    ): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({ updateAttemptsSync(uuid, attempts) }, executor)

    override fun decrementAttempt(uuid: UUID): CompletableFuture<Int?> =
        CompletableFuture.supplyAsync({ decrementAttemptSync(uuid) }, executor)

    override fun markWon(uuid: UUID): CompletableFuture<Unit> =
        CompletableFuture
            .runAsync({
                markWonSync(uuid)
            }, executor)
            .exceptionally { t ->
                MiniGamesCore.plugin.logger.severe("Не удалось изменить данные игрока с UUID $uuid: ${t.message}")
                MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
                return@exceptionally null
            }.thenApply { }

    override fun hasWon(uuid: UUID): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({ hasWonSync(uuid) }, executor).exceptionally { t ->
            MiniGamesCore.plugin.logger.severe("Не удалось получить данные игрока с UUID $uuid: ${t.message}")
            MiniGamesCore.plugin.logger.severe(t.stackTraceToString())
            return@exceptionally null
        }

    // Sync variants of methods (to run in executor)

    private fun getPlayerDataSync(uuid: UUID): PlayerData? {
        val sql = "SELECT uuid, name, attempts, won FROM player_data WHERE uuid = ? LIMIT 1;"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    PlayerData(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getInt("attempts"),
                        rs.getInt("won") != 0,
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun createIfNotExistsSync(
        uuid: UUID,
        name: String,
        initialAttempts: Int,
    ): PlayerData {
        val existing = getPlayerDataSync(uuid)
        if (existing != null) {
            if (existing.name != name) {
                upsertPlayerDataSync(existing.copy(name = name))
            }
            return existing
        }

        val sql = "INSERT OR REPLACE INTO player_data(uuid, name, attempts, won) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, name)
            ps.setInt(3, initialAttempts)
            ps.setInt(4, 0) // false
            ps.executeUpdate()
        }
        return PlayerData(uuid, name, initialAttempts, false)
    }

    private fun upsertPlayerDataSync(playerData: PlayerData) {
        val sql = "INSERT OR REPLACE INTO player_data(uuid, name, attempts, won) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, playerData.uuid.toString())
            ps.setString(2, playerData.name)
            ps.setInt(3, playerData.attempts)
            ps.setInt(4, if (playerData.won) 1 else 0)
            ps.executeUpdate()
        }
    }

    private fun updateAttemptsSync(
        uuid: UUID,
        attempts: Int,
    ): Boolean {
        val sql = "UPDATE player_data SET attempts = ? WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, attempts)
            ps.setString(2, uuid.toString())
            val rows = ps.executeUpdate()
            return rows > 0
        }
    }

    private fun decrementAttemptSync(uuid: UUID): Int? {
        val current = getPlayerDataSync(uuid) ?: return null
        val newVal = current.attempts - 1
        val sql = "UPDATE player_data SET attempts = ? WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, newVal)
            ps.setString(2, uuid.toString())
            ps.executeUpdate()
        }
        return newVal
    }

    private fun markWonSync(uuid: UUID) {
        val sql = "UPDATE player_data SET won = 1 WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            val rows = ps.executeUpdate()
            if (rows > 0) return
        }

        throw IllegalArgumentException("Не удалось найти игрока в БД с UUID $uuid!")
    }

    private fun hasWonSync(uuid: UUID): Boolean {
        val sql = "SELECT won FROM player_data WHERE uuid = ? LIMIT 1;"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    rs.getInt("won") != 0
                } else {
                    throw IllegalArgumentException("Не удалось найти игрока в БД с UUID $uuid!")
                }
            }
        }
    }

    override fun close() {
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
            MiniGamesCore.plugin.logger.warning("Не удалось закрыть выполняющий запросы к БД SQLite поток: ${e.message}")
        }

        try {
            connection.close()
        } catch (e: Exception) {
            MiniGamesCore.plugin.logger.warning("Не удалось закрыть соединение БД SQLite с информацией об игроках: ${e.message}")
        }
    }
}
