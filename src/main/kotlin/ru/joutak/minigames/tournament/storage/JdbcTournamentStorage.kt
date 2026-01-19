package ru.joutak.minigames.tournament.storage

import ru.joutak.minigames.tournament.model.TournamentTeamProgress
import ru.joutak.minigames.tournament.model.TournamentTeamCaptain
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class JdbcTournamentStorage(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val connectTimeoutSeconds: Int,
) : TournamentStorage {

    private fun openConnection(): Connection {
        DriverManager.setLoginTimeout(connectTimeoutSeconds)
        return if (username.isNotBlank() || password.isNotBlank()) {
            DriverManager.getConnection(jdbcUrl, username, password)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }
    }

    override fun ensureSchema(autoCreate: Boolean) {
        if (!autoCreate) return

        openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tournament_team (
                        event_id VARCHAR(64) NOT NULL,
                        team_key VARCHAR(64) NOT NULL,
                        display_name VARCHAR(128) NOT NULL,
                        captain_uuid VARCHAR(36) NULL,
                        captain_name VARCHAR(64) NULL,
                        created_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (event_id, team_key)
                    )
                    """.trimIndent()
                )

                // player_uuid may be NULL for pre-filled rosters by nickname.
                // PK is by (event_id, player_name) to keep name as primary identity.
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tournament_team_member (
                        event_id VARCHAR(64) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        team_key VARCHAR(64) NOT NULL,
                        player_uuid VARCHAR(36) NULL,
                        added_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (event_id, player_name)
                    )
                    """.trimIndent()
                )

                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tournament_team_progress (
                        event_id VARCHAR(64) NOT NULL,
                        stage VARCHAR(64) NOT NULL,
                        team_key VARCHAR(64) NOT NULL,
                        attempts_left INT NOT NULL,
                        won BOOLEAN NOT NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (event_id, stage, team_key)
                    )
                    """.trimIndent()
                )

                tryIndex(st, "CREATE INDEX idx_tournament_member_uuid ON tournament_team_member (event_id, player_uuid)")
                tryIndex(st, "CREATE INDEX idx_tournament_member_team ON tournament_team_member (event_id, team_key)")
                tryIndex(st, "CREATE INDEX idx_tournament_progress_stage ON tournament_team_progress (event_id, stage, won)")
                tryIndex(st, "CREATE INDEX idx_tournament_progress_team ON tournament_team_progress (event_id, team_key, stage)")
            }
        }
    }

    private fun tryIndex(st: java.sql.Statement, sql: String) {
        try {
            st.executeUpdate(sql)
        } catch (_: SQLException) {
            // ignore (index exists / unsupported)
        }
    }

    override fun findTeamKey(eventId: String, playerUuid: UUID, playerName: String): String? {
        val uuidStr = playerUuid.toString()
        val nameLower = playerName.lowercase()

        openConnection().use { conn ->
            // 1) by UUID
            conn.prepareStatement(
                "SELECT team_key FROM tournament_team_member WHERE event_id=? AND player_uuid=? LIMIT 1"
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, uuidStr)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString(1)
                }
            }

            // 2) by name (case-insensitive), and bind UUID if missing
            conn.prepareStatement(
                "SELECT player_name, team_key, player_uuid FROM tournament_team_member WHERE event_id=? AND LOWER(player_name)=? LIMIT 1"
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, nameLower)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null

                    val storedName = rs.getString(1)
                    val teamKey = rs.getString(2)
                    val storedUuid = rs.getString(3)

                    if (storedUuid.isNullOrBlank()) {
                        conn.prepareStatement(
                            "UPDATE tournament_team_member SET player_uuid=? WHERE event_id=? AND player_name=?"
                        ).use { ups ->
                            ups.setString(1, uuidStr)
                            ups.setString(2, eventId)
                            ups.setString(3, storedName)
                            ups.executeUpdate()
                        }
                    }

                    return teamKey
                }
            }
        }
    }

    override fun getTeamCaptain(eventId: String, teamKey: String): TournamentTeamCaptain? {
        openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT captain_uuid, captain_name FROM tournament_team WHERE event_id=? AND team_key=? LIMIT 1"
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, teamKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val uuidStr = rs.getString(1)?.trim().orEmpty()
                    val name = rs.getString(2)?.trim().orEmpty().ifBlank { null }

                    val uuid = if (uuidStr.isNotBlank()) {
                        try {
                            UUID.fromString(uuidStr)
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }

                    if (uuid == null && name == null) return null
                    return TournamentTeamCaptain(uuid = uuid, name = name)
                }
            }
        }
    }

    override fun getTeamDisplayName(eventId: String, teamKey: String): String? {
        openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT display_name FROM tournament_team WHERE event_id=? AND team_key=? LIMIT 1"
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, teamKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        }
    }


    override fun getProgress(eventId: String, stage: String, teamKey: String): TournamentTeamProgress? {
        openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT attempts_left, won FROM tournament_team_progress WHERE event_id=? AND stage=? AND team_key=? LIMIT 1"
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, stage)
                ps.setString(3, teamKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return TournamentTeamProgress(
                        eventId = eventId,
                        stage = stage,
                        teamKey = teamKey,
                        attemptsLeft = rs.getInt(1),
                        won = rs.getBoolean(2),
                    )
                }
            }
        }
    }

    override fun getOrCreateProgress(eventId: String, stage: String, teamKey: String, defaultAttempts: Int): TournamentTeamProgress {
        val existing = getProgress(eventId, stage, teamKey)
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        openConnection().use { conn ->
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO tournament_team_progress (event_id, stage, team_key, attempts_left, won, updated_at_ms)
                    VALUES (?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, eventId)
                    ps.setString(2, stage)
                    ps.setString(3, teamKey)
                    ps.setInt(4, defaultAttempts)
                    ps.setBoolean(5, false)
                    ps.setLong(6, now)
                    ps.executeUpdate()
                }
            } catch (_: SQLException) {
                // race / already created
            }
        }

        return getProgress(eventId, stage, teamKey)
            ?: TournamentTeamProgress(eventId, stage, teamKey, defaultAttempts, false)
    }

    override fun decrementAttempts(eventId: String, stage: String, teamKey: String, delta: Int): TournamentTeamProgress? {
        if (delta <= 0) return getProgress(eventId, stage, teamKey)

        val now = System.currentTimeMillis()
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE tournament_team_progress
                SET attempts_left = CASE WHEN attempts_left - ? < 0 THEN 0 ELSE attempts_left - ? END,
                    updated_at_ms = ?
                WHERE event_id = ? AND stage = ? AND team_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, delta)
                ps.setInt(2, delta)
                ps.setLong(3, now)
                ps.setString(4, eventId)
                ps.setString(5, stage)
                ps.setString(6, teamKey)
                ps.executeUpdate()
            }
        }

        return getProgress(eventId, stage, teamKey)
    }


    override fun ensureMinAttempts(eventId: String, stage: String, teamKey: String, minAttempts: Int): TournamentTeamProgress? {
        if (minAttempts <= 0) return getProgress(eventId, stage, teamKey)

        val now = System.currentTimeMillis()
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE tournament_team_progress
                SET attempts_left = CASE WHEN attempts_left < ? THEN ? ELSE attempts_left END,
                    updated_at_ms = ?
                WHERE event_id = ? AND stage = ? AND team_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, minAttempts)
                ps.setInt(2, minAttempts)
                ps.setLong(3, now)
                ps.setString(4, eventId)
                ps.setString(5, stage)
                ps.setString(6, teamKey)
                ps.executeUpdate()
            }
        }

        return getProgress(eventId, stage, teamKey)
    }

    override fun setWon(eventId: String, stage: String, teamKey: String, won: Boolean) {
        val now = System.currentTimeMillis()
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE tournament_team_progress
                SET won = ?, updated_at_ms = ?
                WHERE event_id = ? AND stage = ? AND team_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setBoolean(1, won)
                ps.setLong(2, now)
                ps.setString(3, eventId)
                ps.setString(4, stage)
                ps.setString(5, teamKey)
                ps.executeUpdate()
            }
        }
    }
}
