package ru.joutak.minigames.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.joutak.minigames.domain.GameResult
import java.io.File
import java.util.*

object GameResultStorage {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val resultsDir = File("game_results").apply { mkdirs() }

    /** Сохраняет результат игры в отдельный JSON-файл по UUID */
    fun save(result: GameResult) {
        val file = File(resultsDir, "game_${result.gameUuid}.json")
        file.writeText(json.encodeToString(result))
    }

    /** Загружает результат по UUID (если файла нет — возвращает null) */
    fun load(gameUuid: UUID): GameResult? {
        val file = File(resultsDir, "game_${gameUuid}.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<GameResult>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Загружает все результаты всех игр */
    fun loadAll(): List<GameResult> {
        if (!resultsDir.exists()) return emptyList()
        return resultsDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<GameResult>(file.readText())
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()
    }

    /** Удаляет файл конкретной игры */
    fun delete(gameUuid: UUID): Boolean {
        val file = File(resultsDir, "game_${gameUuid}.json")
        return file.delete()
    }

    /** Полная очистка папки с результатами */
    fun clearAll() {
        resultsDir.listFiles()?.forEach { it.delete() }
    }
}