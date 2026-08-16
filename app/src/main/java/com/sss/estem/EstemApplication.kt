package com.sss.estem

import android.app.Application
import com.sss.estem.audio.StemPlayerEngine
import com.sss.estem.data.db.EstemDatabase
import com.sss.estem.data.importer.AudioProbe
import com.sss.estem.data.repo.LibraryRepository
import com.sss.estem.data.storage.StemStorage
import com.sss.estem.player.PlayerController
import com.sss.estem.separation.EngineController
import com.sss.estem.separation.PassthroughSeparator
import com.sss.estem.separation.RemoteSeparator
import com.sss.estem.separation.SeparationEnvironment
import com.sss.estem.separation.SeparationScheduler
import com.sss.estem.separation.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI. At this size a container on the Application beats a DI framework — no annotation
 * processor, no version alignment to babysit, and the graph fits on one screen.
 */
class AppContainer(private val application: Application) {

    val storage: StemStorage by lazy { StemStorage(application) }
    val database: EstemDatabase by lazy { EstemDatabase.build(application) }
    val probe: AudioProbe by lazy { AudioProbe(application) }
    val library: LibraryRepository by lazy {
        LibraryRepository(application, database, storage, probe)
    }
    val scheduler: SeparationScheduler by lazy { SeparationScheduler(application) }
    val serverSettings: ServerSettings by lazy { ServerSettings(application) }

    /**
     * Application-scoped for the same reason the player is: the three pages are one device, and
     * swiping between them must not cancel a connection check or reset the chosen engine.
     */
    val engines: EngineController by lazy {
        EngineController(
            context = application,
            scope = networkScope,
            settings = serverSettings,
            passthrough = PassthroughSeparator(application, storage),
            remote = RemoteSeparator(application, serverSettings),
        )
    }

    /**
     * One engine for the whole process. It owns an Oboe stream and a render thread, so creating
     * one per screen would fight over the audio device.
     */
    val engine: StemPlayerEngine by lazy { StemPlayerEngine() }

    /**
     * Application-scoped, not screen-scoped: the three pages are views onto one device, and
     * swiping from the front to the back must not stop playback or reset the sliders.
     */
    val player: PlayerController by lazy {
        PlayerController(library, storage, engine, playerScope)
    }

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class EstemApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Which engine this resolves to is the controller's business, and it re-installs on every
        // change so already-queued work picks the new one up.
        SeparationEnvironment.install(
            library = container.library,
            storage = container.storage,
            separator = container.engines.separator,
        )

        appScope.launch {
            // Anything left RUNNING belongs to a process that no longer exists.
            container.library.reconcile()
            container.scheduler.enqueueAll(container.library.pendingTracks().map { it.id })
        }
    }
}
