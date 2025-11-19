package ru.joutak.minigames.storage

import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import java.io.Closeable
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

class FileStorageWatcher(
    private val file: File,
    private val reloadableStorage: Reloadable,
    private val onReload: Runnable = Runnable {},
) : Closeable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val running = AtomicBoolean(true)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "${file.nameWithoutExtension}-file-watcher-scheduler").apply { isDaemon = true }
        }

    @Volatile
    private var pendingReload: ScheduledFuture<*>? = null
    private val watcherThread: Thread

    init {
        val dir = file.toPath().parent
            ?: throw IllegalArgumentException("Файл должен иметь путь к корневой папке!")
        dir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        watcherThread =
            thread(name = "${file.nameWithoutExtension}-file-storage-watcher") {
                try {
                    while (running.get()) {
                        val key = watchService.take() // blocking
                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            val context = event.context() as? Path ?: continue
                            if (context.name != file.name) continue

                            MiniGamesCore.plugin.logger.info("Обнаружено изменение вида $kind файла ${file.name}")

                            val lastSaved = reloadableStorage.getLastSavedAt()
                            val now = System.currentTimeMillis()

                            if (now - lastSaved <
                                MiniGamesCore.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS) * 2
                            ) {
                                MiniGamesCore.plugin.logger.warning(
                                    "Событие изменение файла ${file.name} проигнорировано из-за недавнего сохранения файла плагином.",
                                )
                                break
                            }

                            scheduleReload()
                        }
                        key.reset()
                    }
                } catch (_: InterruptedException) {
                    MiniGamesCore.plugin.logger.warning("Поток ${Thread.currentThread().name} был прерван.")
                } catch (t: Throwable) {
                    MiniGamesCore.plugin.logger.warning("Ошибка в потоке ${Thread.currentThread().name}: ${t.message}")
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
            scheduler.schedule(
                {
                    reloadableStorage.reload().thenRun(onReload).exceptionally { t ->
                        MiniGamesCore.plugin.logger.warning("Ошибка при планировании перезагрузки файла ${file.name}: ${t.message}")
                        return@exceptionally null
                    }
                },
                MiniGamesCore.configuration.get(ConfigKeys.STORAGE_DEBOUNCE_MILLIS),
                TimeUnit.MILLISECONDS
            )
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
            MiniGamesCore.plugin.logger.fine("Ошибка выключения планировщика ${file.nameWithoutExtension}-file-watcher: ${t.message}")
            scheduler.shutdownNow()
        }

        try {
            watchService.close()
        } catch (_: Exception) {
        }
    }
}