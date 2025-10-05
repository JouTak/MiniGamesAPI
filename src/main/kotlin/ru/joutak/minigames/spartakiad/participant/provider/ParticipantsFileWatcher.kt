package ru.joutak.minigames.spartakiad.participant.provider

import org.bukkit.Bukkit
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.event.ParticipantsListChangeEvent
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.name

class ParticipantsFileWatcher(
    private val participantsFile: File,
    private val participantsProvider: ParticipantsProvider,
) : AutoCloseable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val running = AtomicBoolean(true)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "participants-watcher-scheduler").apply { isDaemon = true }
        }

    @Volatile
    private var pendingReload: ScheduledFuture<*>? = null
    private val watcherThread: Thread

    init {
        val dir = participantsFile.toPath().parent ?: throw IllegalArgumentException("Файл должен иметь путь к корневой папке!")
        dir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        watcherThread =
            thread(name = "participants-watcher") {
                try {
                    while (running.get()) {
                        val key = watchService.take() // blocking
                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            val context = event.context() as? Path ?: continue
                            if (context.name != participantsFile.name) continue

                            MiniGamesPlugin.instance.logger.info("Обнаружено изменение вида $kind файла ${participantsFile.name}")

                            val providerLastSaved = participantsProvider.getLastSavedAt()
                            val now = System.currentTimeMillis()

                            if (now - providerLastSaved <
                                MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS) * 2
                            ) {
                                MiniGamesPlugin.instance.logger.warning(
                                    "Событие изменение файла участников проигнорировано из-за недавнего сохранения файла плагином.",
                                )
                                break
                            }

                            scheduleReload()
                        }
                        key.reset()
                    }
                } catch (e: InterruptedException) {
                    MiniGamesPlugin.instance.logger.warning("Поток ${Thread.currentThread().name} был прерван.")
                } catch (t: Throwable) {
                    MiniGamesPlugin.instance.logger.warning("Ошибка в потоке ${Thread.currentThread().name}: ${t.message}")
                } finally {
                    try {
                        watchService.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
    }

    private fun scheduleReload() {
        pendingReload?.cancel(false)
        pendingReload =
            scheduler.schedule({
                try {
                    participantsProvider.reload().thenRun {
                        Bukkit.getScheduler().runTask(
                            MiniGamesPlugin.instance,
                            Runnable {
                                Bukkit.getPluginManager().callEvent(ParticipantsListChangeEvent())
                            },
                        )
                    }
                } catch (t: Throwable) {
                    MiniGamesPlugin.instance.logger.warning("Ошибка при планировании перезагрузки файла с участниками: ${t.message}")
                }
            }, MiniGamesPlugin.instance.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        running.set(false)
        try {
            watcherThread.interrupt()
        } catch (_: Exception) {
        }

        try {
            pendingReload?.cancel(false)
        } catch (_: Throwable) {
            // ignore
        }

        try {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (t: Throwable) {
            MiniGamesPlugin.instance.logger.fine("Ошибка выключения планировщика participants-watcher-scheduler: ${t.message}")
            scheduler.shutdownNow()
        }

        try {
            watchService.close()
        } catch (_: Exception) {
        }
    }
}
