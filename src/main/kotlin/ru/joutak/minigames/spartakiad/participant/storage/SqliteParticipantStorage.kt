package ru.joutak.minigames.spartakiad.participant.storage

import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.Participant
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SqliteParticipantStorage(
    private val databaseFile: File,
) : ParticipantStorage {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "participants-sqlite-${databaseFile.name}").apply { isDaemon = true }
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
                    st.execute("PRAGMA encoding = \"UTF-8\"")
                    st.execute("PRAGMA foreign_keys = ON;")
                    st.execute("PRAGMA busy_timeout = 5000;")
                }
            }

        createOrUpdateTable()
    }

    private fun createOrUpdateTable() {
        val sql =
            """
            CREATE TABLE IF NOT EXISTS participants(
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
        val infoSql = "PRAGMA table_info(participants);"
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
                st.execute("ALTER TABLE participants ADD COLUMN won INTEGER NOT NULL DEFAULT 0;")
            }
        }
    }

    // Async methods

    override fun getParticipant(uuid: UUID): CompletableFuture<Participant?> =
        CompletableFuture.supplyAsync({
            getParticipantSync(uuid)
        }, executor)

    override fun createIfNotExists(
        uuid: UUID,
        name: String,
        initialAttempts: Int,
    ): CompletableFuture<Participant> =
        CompletableFuture.supplyAsync({ createIfNotExistsSync(uuid, name, initialAttempts) }, executor)

    override fun updateParticipant(participant: Participant): CompletableFuture<Unit> =
        CompletableFuture.runAsync({ updateParticipantSync(participant) }, executor).thenApply {}

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
            return@exceptionally false
        }

    // Sync variants of methods (to run in executor)

    private fun getParticipantSync(uuid: UUID): Participant? {
        val sql = "SELECT uuid, name, attempts, won FROM participants WHERE uuid = ? LIMIT 1;"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    Participant(
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
    ): Participant {
        val existing = getParticipantSync(uuid)
        if (existing != null) {
            if (existing.name != name) {
                val updated = Participant(
                    existing.uuid,
                    name,
                    existing.attempts,
                    existing.won
                )
                updateParticipantSync(
                    updated,
                )
                return updated
            }
            return existing
        }

        val sql = "INSERT OR REPLACE INTO participants(uuid, name, attempts, won) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, name)
            ps.setInt(3, initialAttempts)
            ps.setInt(4, 0) // 0 == false
            ps.executeUpdate()
        }
        return Participant(uuid, name, initialAttempts, false)
    }

    private fun updateParticipantSync(participant: Participant) {
        val sql = "INSERT OR REPLACE INTO participants(uuid, name, attempts, won) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, participant.uuid.toString())
            ps.setString(2, participant.name)
            ps.setInt(3, participant.attempts)
            ps.setInt(4, if (participant.won) 1 else 0)
            ps.executeUpdate()
        }
    }

    private fun updateAttemptsSync(
        uuid: UUID,
        attempts: Int,
    ): Boolean {
        val sql = "UPDATE participants SET attempts = ? WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, attempts)
            ps.setString(2, uuid.toString())
            val rows = ps.executeUpdate()
            return rows > 0
        }
    }

    private fun decrementAttemptSync(uuid: UUID): Int? {
        val current = getParticipantSync(uuid) ?: return null
        val newVal = current.attempts - 1
        val sql = "UPDATE participants SET attempts = ? WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, newVal)
            ps.setString(2, uuid.toString())
            ps.executeUpdate()
        }
        return newVal
    }

    private fun markWonSync(uuid: UUID) {
        val sql = "UPDATE participants SET won = 1 WHERE uuid = ?;"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, uuid.toString())
            val rows = ps.executeUpdate()
            if (rows > 0) return
        }

        throw IllegalArgumentException("Не удалось найти игрока в БД с UUID $uuid!")
    }

    private fun hasWonSync(uuid: UUID): Boolean {
        val sql = "SELECT won FROM participants WHERE uuid = ? LIMIT 1;"
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
