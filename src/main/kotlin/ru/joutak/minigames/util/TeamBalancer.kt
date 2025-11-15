package ru.joutak.minigames.util

import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.Player
import ru.joutak.minigames.domain.Team
import java.io.File

object TeamBalancer {

    /**
     * Стандартный метод: распределяет игроков из списка.
     */
    fun distributePlayers(players: List<Player>, teamCount: Int): List<Team> {
        require(teamCount > 0) { "Количество команд должно быть больше нуля" }

        val teams = List(teamCount) { index -> Team("Team ${index + 1}") }

        players.forEachIndexed { index, player ->
            teams[index % teamCount].add(player)
        }

        return teams
    }

    /**
     * Новый метод: берет путь к файлу из config.yml
     * teams.players_file: "teams.txt"
     */
    fun distributeAuto(teamCount: Int): List<Team> {
        val config = MiniGamesCore.configuration
        val fileName = config.get(ConfigKeys.TEAM_PLAYER_FILE)

        val file = MiniGamesCore.plugin.dataFolder
            .toPath()
            .resolve(fileName)
            .toFile()

        return distributeFromFile(file, teamCount)
    }

    /**
     * Новый метод: читает игроков из указанного файла.
     */
    fun distributeFromFile(file: File, teamCount: Int): List<Team> {
        if (!file.exists()) {
            throw IllegalArgumentException("Файл ${file.absolutePath} не найден!")
        }

        val players = file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Player(it) }

        return distributePlayers(players, teamCount)
    }
}
