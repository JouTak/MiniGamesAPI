package ru.joutak.minigames.storage;

import java.util.concurrent.CompletableFuture

interface Reloadable {
    fun getLastSavedAt(): Long

    fun reload(): CompletableFuture<Unit>
}
