package com.sss.estem.separation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeparationEngine { PASSTHROUGH, REMOTE }

data class EngineState(
    val engine: SeparationEngine,
    val serverUrl: String,
    val probing: Boolean,
    val health: ServerHealth?,
    val error: String?,
)

/**
 * Owns which separator is installed and what it is pointed at.
 *
 * Process-scoped rather than screen-scoped, and the choice is persisted, because "which engine
 * produced these stems" outlives any one screen or session.
 *
 * Choosing the server when the server is down does **not** quietly fall back to passthrough. A
 * silent fallback would fill the stem cache with four copies of the mix that look exactly like a
 * successful separation; failing the track instead says what happened and leaves ⋮ → Separate
 * again as the fix.
 */
class EngineController(
    context: Context,
    private val scope: CoroutineScope,
    val settings: ServerSettings,
    private val passthrough: StemSeparator,
    private val remote: RemoteSeparator,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        EngineState(
            engine = readEngine(),
            serverUrl = settings.baseUrl.value,
            probing = false,
            health = null,
            error = null,
        ),
    )

    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var probeJob: Job? = null

    val separator: StemSeparator
        get() = if (_state.value.engine == SeparationEngine.REMOTE) remote else passthrough

    fun select(engine: SeparationEngine) {
        if (_state.value.engine == engine) return
        preferences.edit().putString(KEY_ENGINE, engine.name).apply()
        _state.update { it.copy(engine = engine) }
        SeparationEnvironment.separator = separator
        if (engine == SeparationEngine.REMOTE) probe()
    }

    /** Keeps the typed address in state without committing it until [saveServerUrl]. */
    fun editServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url, health = null, error = null) }
    }

    fun saveServerUrl() {
        settings.set(_state.value.serverUrl)
        probe()
    }

    fun probe() {
        probeJob?.cancel()
        _state.update { it.copy(probing = true, health = null, error = null) }
        probeJob = scope.launch {
            val result = remote.health(_state.value.serverUrl)
            _state.update {
                it.copy(
                    probing = false,
                    health = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { error ->
                        error.message?.takeIf(String::isNotBlank) ?: "Could not reach the server"
                    },
                )
            }
        }
    }

    init {
        // The header states a connection status the moment the page is looked at, so it should
        // state a true one: check once at startup rather than waiting for someone to press
        // Save & test on an address that has not changed since last time.
        if (_state.value.engine == SeparationEngine.REMOTE) probe()
    }

    private fun readEngine(): SeparationEngine =
        preferences.getString(KEY_ENGINE, null)
            ?.let { name -> SeparationEngine.entries.firstOrNull { it.name == name } }
            ?: SeparationEngine.PASSTHROUGH

    private companion object {
        const val PREFS = "separation"
        const val KEY_ENGINE = "engine"
    }
}
