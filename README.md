# MiniGames API

Библиотека для Paper-плагинов, упрощающая разработку мини-игр: очередь игроков, балансировка команд, сохранение результатов и интеграция команд через Brigadier.

---

## 📌 Возможности

- Глобальная очередь игроков (join/leave)
- Балансировка игроков по командам
- Загрузка игроков из файла
- Хранение результатов игр в JSON
- Готовые Brigadier-команды
- Использование как отдельной библиотеки в любом Paper-плагине

---

## 📦 Установка

Добавьте JAR в зависимости вашего плагина  
или подключите как библиотеку через Maven/Gradle.

---

## 🚀 Использование

### 1. Очередь игроков

Добавление игрока:

```kotlin
val domainPlayer = GameQueue.getOrCreatePlayer(bukkitPlayer)
GameQueue.addPlayer(domainPlayer)
```
Удаление игрока:
```kotlin
val domainPlayer = GameQueue.getOrCreatePlayer(bukkitPlayer)
GameQueue.removePlayer(domainPlayer)
```
Методы автоматически обрабатывают случаи, когда игрок уже есть или отсутствует в очереди.

### 2. Балансировка команд 

Балансировка из списка:
```kotlin
val players: List<Player> = listOf(
Player("User1"),
Player("User2"),
Player("User3")
)

val teams = TeamBalancer.distributePlayers(players, teamCount = 2)
```
Используется механизм round-robin.

Автоматическая балансировка (из файла):

В config.yml:
```yaml
teams:
    players_file: "teams.txt"
```
В коде:
```kotlin
val result = TeamBalancer.distributeAuto(teamCount = 2)
```
Формат файла:
```nginx
PlayerOne
PlayerTwo
PlayerThree
```

Балансировка из произвольного файла:
```kotlin
val teams = TeamBalancer.distributeFromFile(File("teams.txt"), 2)
```

### 3. Хранение результатов игр (JSON)
Файлы сохраняются в директорию:
```bash
/game_results/
```
Сохранение результата:
```kotlin
GameResultStorage.save(result)
```
Загрузка по UUID:
```kotlin
val result = GameResultStorage.load(uuid)
```
Загрузка всех результатов:
```kotlin
val all = GameResultStorage.loadAll()
```
Удаление:
```kotlin
GameResultStorage.delete(uuid)
```
Очистка:
```kotlin
GameResultStorage.clearAll()
```
## Команды

### /ready
Добавляет игрока в очередь. Использовать могут только игроки.

### /unready
Удаляет игрока из очереди. Только игроки.

### /mg
Корневая команда без логики. Используется как контейнер для подкоманд.

### /start <string>
Команда с аргументом `string`. Пример расширения:
```kotlin
val startCommand = StartCommand.getBuilder()
