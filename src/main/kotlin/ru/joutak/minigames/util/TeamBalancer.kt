package ru.joutak.minigames.util

import ru.joutak.minigames.domain.Player
import ru.joutak.minigames.domain.Team

object TeamBalancer {

    /**
     * Разбивает игроков на команды.
     *
     * @param players Список игроков
     * @param teamCount Количество команд
     * @return Список команд с распределёнными игроками
     */
    fun distributePlayers(players: List<Player>, teamCount: Int): List<Team> {
        require(teamCount > 0) { "Количество команд должно быть больше нуля" }

        // Создаём команды с названиями "Team 1", "Team 2", ...
        val teams = List(teamCount) { index -> Team("Team ${index + 1}") }

        // Распределяем игроков циклично
        players.forEachIndexed { index, player ->
            val teamIndex = index % teamCount
            teams[teamIndex].add(player)
        }

        return teams
    }
}
