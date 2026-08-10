package ru.joutak.minigames.results.storage

import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.MatchTeamsSnapshot
import ru.joutak.minigames.results.model.Metric
import ru.joutak.minigames.results.model.TeamMetricsSnapshot
import ru.joutak.minigames.results.model.CompletionStatus
import ru.joutak.minigames.results.model.TopPlayerIntMetric
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class JdbcResultsStorage(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val driverClass: String,
    private val connectTimeoutSeconds: Int,
    private val autoCreateSchema: Boolean,
) : ResultsStorage {

    init {
        if (driverClass.isNotBlank()) {
            Class.forName(driverClass)
        }

        if (autoCreateSchema) {
            ensureSchema()
        }
    }

    private fun openConnection(): Connection {
        // DriverManager timeout is global, but is the most portable option without pooling.
        DriverManager.setLoginTimeout(connectTimeoutSeconds)

        return if (username.isNotBlank() || password.isNotBlank()) {
            DriverManager.getConnection(jdbcUrl, username, password)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }
    }

    override fun ensureSchema() {
        openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS matches (
                        match_id VARCHAR(36) PRIMARY KEY,
                        mode_key VARCHAR(64) NOT NULL,
                        map_key VARCHAR(128) NULL,
                        server_id VARCHAR(64) NULL,
                        started_at_ms BIGINT NOT NULL,
                        ended_at_ms BIGINT NOT NULL,
                        duration_ms BIGINT NOT NULL,
                        event_id VARCHAR(64) NULL,
                        stage VARCHAR(64) NULL,
                        group_key VARCHAR(64) NULL,
                        rule_set VARCHAR(64) NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS match_teams (
                        match_id VARCHAR(36) NOT NULL,
                        team_id INT NOT NULL,
                        placement INT NULL,
                        is_winner BOOLEAN NOT NULL,
                        score DOUBLE PRECISION NULL,
                        PRIMARY KEY (match_id, team_id)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS match_players (
                        match_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NULL,
                        team_id INT NULL,
                        is_winner BOOLEAN NOT NULL,
                        joined_at_ms BIGINT NULL,
                        left_at_ms BIGINT NULL,
                        PRIMARY KEY (match_id, player_uuid)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS player_metrics (
                        match_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        metric_key VARCHAR(64) NOT NULL,
                        value_int BIGINT NULL,
                        value_real DOUBLE PRECISION NULL,
                        value_text TEXT NULL,
                        PRIMARY KEY (match_id, player_uuid, metric_key)
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS team_metrics (
                        match_id VARCHAR(36) NOT NULL,
                        team_id INT NOT NULL,
                        metric_key VARCHAR(64) NOT NULL,
                        value_int BIGINT NULL,
                        value_real DOUBLE PRECISION NULL,
                        value_text TEXT NULL,
                        PRIMARY KEY (match_id, team_id, metric_key)
                    )
                    """.trimIndent()
                )

                // Indexes are best-effort (MySQL may not support IF NOT EXISTS).
                tryExecuteIndex(st, "CREATE INDEX IF NOT EXISTS idx_matches_mode_ended ON matches (mode_key, ended_at_ms)")
                tryExecuteIndex(st, "CREATE INDEX IF NOT EXISTS idx_matches_event_stage_mode ON matches (event_id, stage, mode_key)")
                tryExecuteIndex(st, "CREATE INDEX IF NOT EXISTS idx_match_players_uuid ON match_players (player_uuid)")
                tryExecuteIndex(st, "CREATE INDEX IF NOT EXISTS idx_player_metrics_key_int ON player_metrics (metric_key, value_int)")
            }
        }
    }

    private fun tryExecuteIndex(st: java.sql.Statement, sql: String) {
        try {
            st.executeUpdate(sql)
        } catch (_: SQLException) {
            // ignore
        }
    }

    override fun recordMatch(result: MatchResult): Boolean {
        openConnection().use { conn ->
            conn.autoCommit = false
            try {
                deleteMatch(conn, result.matchId)
                insertMatch(conn, result)
                insertTeams(conn, result)
                insertPlayers(conn, result)
                insertTeamMetrics(conn, result)
                insertPlayerMetrics(conn, result)
                conn.commit()
                return true
            } catch (t: Throwable) {
                try {
                    conn.rollback()
                } catch (_: Throwable) {
                }
                throw t
            } finally {
                try {
                    conn.autoCommit = true
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun deleteMatch(conn: Connection, matchId: UUID) {
        val id = matchId.toString()
        conn.prepareStatement("DELETE FROM player_metrics WHERE match_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM team_metrics WHERE match_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM match_players WHERE match_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM match_teams WHERE match_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM matches WHERE match_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    private fun insertMatch(conn: Connection, result: MatchResult) {
        val ctx = result.context
        conn.prepareStatement(
            """
            INSERT INTO matches (
                match_id, mode_key, map_key, server_id,
                started_at_ms, ended_at_ms, duration_ms,
                event_id, stage, group_key, rule_set
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, result.matchId.toString())
            ps.setString(2, result.modeKey)
            ps.setString(3, result.mapKey)
            ps.setString(4, result.serverId)
            ps.setLong(5, result.startedAtMs)
            ps.setLong(6, result.endedAtMs)
            ps.setLong(7, result.durationMs)
            ps.setString(8, ctx?.eventId)
            ps.setString(9, ctx?.stage)
            ps.setString(10, ctx?.groupKey)
            ps.setString(11, ctx?.ruleSet)
            ps.executeUpdate()
        }
    }

    private fun insertTeams(conn: Connection, result: MatchResult) {
        conn.prepareStatement(
            """
            INSERT INTO match_teams (match_id, team_id, placement, is_winner, score)
            VALUES (?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (t in result.teams) {
                ps.setString(1, result.matchId.toString())
                ps.setInt(2, t.teamId)
                if (t.placement != null) ps.setInt(3, t.placement) else ps.setNull(3, java.sql.Types.INTEGER)
                ps.setBoolean(4, t.isWinner)
                if (t.score != null) ps.setDouble(5, t.score) else ps.setNull(5, java.sql.Types.DOUBLE)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertPlayers(conn: Connection, result: MatchResult) {
        conn.prepareStatement(
            """
            INSERT INTO match_players (
                match_id, player_uuid, player_name, team_id, is_winner, joined_at_ms, left_at_ms
            ) VALUES (?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (p in result.players) {
                ps.setString(1, result.matchId.toString())
                ps.setString(2, p.playerUuid.toString())
                ps.setString(3, p.playerName)
                if (p.teamId != null) ps.setInt(4, p.teamId) else ps.setNull(4, java.sql.Types.INTEGER)
                ps.setBoolean(5, p.isWinner)
                if (p.joinedAtMs != null) ps.setLong(6, p.joinedAtMs) else ps.setNull(6, java.sql.Types.BIGINT)
                if (p.leftAtMs != null) ps.setLong(7, p.leftAtMs) else ps.setNull(7, java.sql.Types.BIGINT)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertPlayerMetrics(conn: Connection, result: MatchResult) {
        conn.prepareStatement(
            """
            INSERT INTO player_metrics (match_id, player_uuid, metric_key, value_int, value_real, value_text)
            VALUES (?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (p in result.players) {
                for (m in p.metrics) {
                    ps.setString(1, result.matchId.toString())
                    ps.setString(2, p.playerUuid.toString())
                    ps.setString(3, m.key)
                    if (m.valueInt != null) ps.setLong(4, m.valueInt) else ps.setNull(4, java.sql.Types.BIGINT)
                    if (m.valueReal != null) ps.setDouble(5, m.valueReal) else ps.setNull(5, java.sql.Types.DOUBLE)
                    ps.setString(6, m.valueText)
                    ps.addBatch()
                }
            }
            ps.executeBatch()
        }
    }

    private fun insertTeamMetrics(conn: Connection, result: MatchResult) {
        conn.prepareStatement(
            """
            INSERT INTO team_metrics (match_id, team_id, metric_key, value_int, value_real, value_text)
            VALUES (?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (t in result.teams) {
                val metrics = t.metrics.filterNot { it.key == COMPLETION_STATUS_METRIC_KEY } +
                    Metric.text(COMPLETION_STATUS_METRIC_KEY, t.completionStatus.name)
                for (m in metrics) {
                    ps.setString(1, result.matchId.toString())
                    ps.setInt(2, t.teamId)
                    ps.setString(3, m.key)
                    if (m.valueInt != null) ps.setLong(4, m.valueInt) else ps.setNull(4, java.sql.Types.BIGINT)
                    if (m.valueReal != null) ps.setDouble(5, m.valueReal) else ps.setNull(5, java.sql.Types.DOUBLE)
                    ps.setString(6, m.valueText)
                    ps.addBatch()
                }
            }
            ps.executeBatch()
        }
    }

    override fun hasPlayerWon(eventId: String, stage: String, modeKey: String, playerUuid: UUID): Boolean {
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT 1
                FROM match_players mp
                JOIN matches m ON m.match_id = mp.match_id
                WHERE m.event_id = ? AND m.stage = ? AND m.mode_key = ?
                  AND mp.player_uuid = ? AND mp.is_winner = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, stage)
                ps.setString(3, modeKey)
                ps.setString(4, playerUuid.toString())
                ps.setBoolean(5, true)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    override fun getTopPlayerIntMetric(
        modeKey: String,
        metricKey: String,
        limit: Int,
        eventId: String?,
        stage: String?,
    ): List<TopPlayerIntMetric> {
        val safeLimit = limit.coerceIn(1, 1000)
        val resultList = mutableListOf<TopPlayerIntMetric>()

        openConnection().use { conn ->
            val sql = buildString {
                append(
                    """
                    SELECT pm.match_id, pm.player_uuid, mp.player_name, pm.value_int
                    FROM player_metrics pm
                    JOIN matches m ON m.match_id = pm.match_id
                    JOIN match_players mp ON mp.match_id = pm.match_id AND mp.player_uuid = pm.player_uuid
                    WHERE m.mode_key = ? AND pm.metric_key = ? AND pm.value_int IS NOT NULL
                    """.trimIndent()
                )
                if (eventId != null) append(" AND m.event_id = ?")
                if (stage != null) append(" AND m.stage = ?")
                append(" ORDER BY pm.value_int DESC")
                append(" LIMIT ?")
            }

            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, modeKey)
                ps.setString(idx++, metricKey)
                if (eventId != null) ps.setString(idx++, eventId)
                if (stage != null) ps.setString(idx++, stage)
                ps.setInt(idx, safeLimit)

                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        resultList.add(
                            TopPlayerIntMetric(
                                matchId = UUID.fromString(rs.getString(1)),
                                playerUuid = UUID.fromString(rs.getString(2)),
                                playerName = rs.getString(3),
                                value = rs.getLong(4)
                            )
                        )
                    }
                }
            }
        }

        return resultList
    }

    override fun loadMatchTeamsWithMetrics(
        eventId: String,
        stage: String,
        endedAtMaxInclusive: Long?,
        limit: Int,
        offset: Int,
    ): List<MatchTeamsSnapshot> {
        val safeLimit = limit.coerceIn(1, 2000)
        val safeOffset = offset.coerceAtLeast(0)

        openConnection().use { conn ->
            val matchIds = ArrayList<UUID>()
            val endedById = HashMap<UUID, Long>()

            val sql = buildString {
                append("SELECT match_id, ended_at_ms FROM matches WHERE event_id = ? AND stage = ?")
                if (endedAtMaxInclusive != null) append(" AND ended_at_ms <= ?")
                append(" ORDER BY ended_at_ms ASC, match_id ASC LIMIT ? OFFSET ?")
            }

            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, eventId)
                ps.setString(idx++, stage)
                if (endedAtMaxInclusive != null) {
                    ps.setLong(idx++, endedAtMaxInclusive)
                }
                ps.setInt(idx++, safeLimit)
                ps.setInt(idx, safeOffset)

                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = UUID.fromString(rs.getString(1))
                        val ended = rs.getLong(2)
                        matchIds.add(id)
                        endedById[id] = ended
                    }
                }
            }

            if (matchIds.isEmpty()) return emptyList()

            val placeholders = matchIds.joinToString(",") { "?" }
            val teamsByMatch = HashMap<UUID, LinkedHashMap<Int, TeamAcc>>(matchIds.size)
            for (id in matchIds) {
                teamsByMatch[id] = LinkedHashMap()
            }

            val sqlTeams = ("""
                SELECT mt.match_id, mt.team_id, mt.placement, mt.is_winner, mt.score,
                       tm.metric_key, tm.value_int, tm.value_real, tm.value_text
                FROM match_teams mt
                LEFT JOIN team_metrics tm ON tm.match_id = mt.match_id AND tm.team_id = mt.team_id
                WHERE mt.match_id IN (""" + placeholders + """)
                ORDER BY mt.match_id ASC, mt.team_id ASC, tm.metric_key ASC
            """).trimIndent()

            conn.prepareStatement(sqlTeams).use { ps ->
                var idx = 1
                for (id in matchIds) {
                    ps.setString(idx++, id.toString())
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val matchId = UUID.fromString(rs.getString(1))
                        val teamId = rs.getInt(2)

                        val teamMap = teamsByMatch[matchId] ?: continue
                        val acc = teamMap.getOrPut(teamId) { TeamAcc(teamId) }

                        val placementObj = rs.getObject(3)
                        if (placementObj != null) {
                            acc.placement = (placementObj as Number).toInt()
                        }

                        acc.isWinner = rs.getBoolean(4)

                        val scoreObj = rs.getObject(5)
                        if (scoreObj != null) {
                            acc.score = (scoreObj as Number).toDouble()
                        }

                        val metricKey = rs.getString(6)
                        if (metricKey != null) {
                            val vIntObj = rs.getObject(7)
                            val vRealObj = rs.getObject(8)
                            val vText = rs.getString(9)
                            if (metricKey == COMPLETION_STATUS_METRIC_KEY) {
                                acc.completionStatus = runCatching { CompletionStatus.valueOf(vText) }
                                    .getOrDefault(CompletionStatus.FINISHED)
                            } else {
                                acc.metrics.add(
                                    Metric(
                                        key = metricKey,
                                        valueInt = (vIntObj as? Number)?.toLong(),
                                        valueReal = (vRealObj as? Number)?.toDouble(),
                                        valueText = vText,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val out = ArrayList<MatchTeamsSnapshot>(matchIds.size)
            for (id in matchIds) {
                val ended = endedById[id] ?: continue
                val teamMap = teamsByMatch[id] ?: continue
                val teams = teamMap.values.map { acc ->
                    TeamMetricsSnapshot(
                        teamId = acc.teamId,
                        placement = acc.placement,
                        isWinner = acc.isWinner,
                        score = acc.score,
                        metrics = acc.metrics.toList(),
                        completionStatus = acc.completionStatus,
                    )
                }
                out.add(MatchTeamsSnapshot(id, ended, teams))
            }

            return out
        }
    }

    override fun getMatchEndedAtMs(matchId: UUID): Long? {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT ended_at_ms FROM matches WHERE match_id = ? LIMIT 1").use { ps ->
                ps.setString(1, matchId.toString())
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong(1) else null
                }
            }
        }
    }

    private data class TeamAcc(
        val teamId: Int,
        var placement: Int? = null,
        var isWinner: Boolean = false,
        var score: Double? = null,
        var completionStatus: CompletionStatus = CompletionStatus.FINISHED,
        val metrics: MutableList<Metric> = mutableListOf(),
    )

    private companion object {
        const val COMPLETION_STATUS_METRIC_KEY = "minigamesapi.completion_status"
    }

    override fun close() {
        // no-op: we open a new connection per operation
    }
}
