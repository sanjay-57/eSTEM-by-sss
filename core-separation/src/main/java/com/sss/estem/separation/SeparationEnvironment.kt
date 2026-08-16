package com.sss.estem.separation

import com.sss.estem.data.repo.LibraryRepository
import com.sss.estem.data.storage.StemStorage

/**
 * WorkManager constructs workers reflectively, so they cannot be handed dependencies through a
 * constructor without a DI framework's worker factory. This is the seam instead: the application
 * fills it in at startup and [SeparationWorker] reads from it.
 *
 * [separator] is a `var` on purpose — settings can switch between passthrough and htdemucs at
 * runtime, and already-queued work should pick up the change.
 */
object SeparationEnvironment {

    private var libraryOrNull: LibraryRepository? = null
    private var storageOrNull: StemStorage? = null

    @Volatile
    var separator: StemSeparator? = null

    val isInitialised: Boolean
        get() = libraryOrNull != null && storageOrNull != null && separator != null

    val library: LibraryRepository
        get() = requireNotNull(libraryOrNull) { "SeparationEnvironment not installed" }

    val storage: StemStorage
        get() = requireNotNull(storageOrNull) { "SeparationEnvironment not installed" }

    fun install(
        library: LibraryRepository,
        storage: StemStorage,
        separator: StemSeparator,
    ) {
        this.libraryOrNull = library
        this.storageOrNull = storage
        this.separator = separator
    }
}
