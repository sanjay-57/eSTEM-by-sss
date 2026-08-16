package com.sss.estem.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sss.estem.AppContainer
import com.sss.estem.audio.BeatDetector
import com.sss.estem.audio.Effect
import com.sss.estem.data.db.Recording
import com.sss.estem.data.db.TrackWithStems
import com.sss.estem.data.model.SeparationState
import com.sss.estem.player.PlayerController
import com.sss.estem.data.repo.ImportOutcome
import com.sss.estem.data.repo.LibraryRepository
import com.sss.estem.design.LedPalette
import com.sss.estem.separation.EngineState
import com.sss.estem.separation.SeparationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything that is not the object: the offline library, the four recording slots and the
 * effects list.
 *
 * Adding music is the first thing on the page rather than a buried menu item, because on this
 * app it is the only way anything gets in — there is no catalog behind it.
 */
@Composable
fun QueuePage(
    container: AppContainer,
    onPlay: (TrackWithStems) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tracks by container.player.queue.collectAsStateWithLifecycle(emptyList())
    val recordings by container.player.recordings.collectAsStateWithLifecycle(emptyList())
    val playerState by container.player.state.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val outcomes = container.library.import(uris)
            importing = false
            // Separation is the slow part, so it starts the moment the row exists rather than
            // waiting for the track to be opened.
            container.scheduler.enqueueAll(
                outcomes.filterIsInstance<ImportOutcome.Added>().map { it.trackId },
            )
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 72.dp),
        ) {
            item {
                Text("Queue", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { picker.launch(LibraryRepository.ACCEPTED_MIME_TYPES) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (importing) "Importing…" else "Add music from this phone")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "FLAC, MP3, WAV, AAC, AIFF, OGG. Each track is split into four stems once, " +
                        "on this device, then plays instantly forever.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                EngineSection(container)
                Spacer(Modifier.height(20.dp))
            }

            if (tracks.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Keys are prefixed per section: a track id and a recording id are both plain
                // Longs and would collide the moment slot 1 and track 1 both exist.
                items(tracks, key = { "track-${it.track.id}" }) { entry ->
                    TrackRow(
                        entry = entry,
                        loadedOn = playerState.decks
                            .indexOfFirst { it.loaded && it.trackId == entry.track.id },
                        hasGrid = (entry.stems?.bpm ?: 0f) > 0f,
                        onPlay = { onPlay(entry) },
                        onCue = { container.player.cue(entry) },
                        onAnalyse = {
                            scope.launch {
                                val stems = container.library.stems(entry.track.id)
                                    ?: return@launch
                                val grid = withContext(Dispatchers.IO) {
                                    BeatDetector.analyse(
                                        drums = java.io.File(stems.drumsPath),
                                        sampleRate = stems.sampleRate,
                                        channelCount = stems.channelCount,
                                    )
                                }
                                container.library.updateBeatGrid(
                                    trackId = entry.track.id,
                                    bpm = grid?.bpm ?: 0f,
                                    firstBeatFrame = grid?.firstBeatFrame ?: 0L,
                                )
                            }
                        },
                        onRequeue = {
                            scope.launch {
                                container.scheduler.cancel(entry.track.id)
                                container.library.requeue(entry.track.id)
                                container.scheduler.enqueue(entry.track.id)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                container.scheduler.cancel(entry.track.id)
                                container.library.delete(entry.track.id)
                            }
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Recordings", "${recordings.size} of 4 slots")
            }

            if (recordings.isEmpty()) {
                item {
                    Text(
                        "Hold volume-up on the front page to capture 60 seconds of your mix.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(recordings, key = { "rec-${it.id}" }) { RecordingRow(it) }
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Effects", "one at a time, like the hardware")
                Spacer(Modifier.height(6.dp))
            }

            items(Effect.entries.toList(), key = { "fx-${it.name}" }) { effect ->
                EffectRow(
                    effect = effect,
                    selected = playerState.effect == effect,
                    onSelect = { container.player.selectEffect(effect) },
                )
            }
        }
    }
}

/**
 * Which engine splits a track, and where the machine that does it lives.
 *
 * It sits above the queue rather than behind a settings screen because on a fresh install the
 * answer is "passthrough", and passthrough is not separation — every slider moves the whole mix.
 * Nothing else on this page tells you that.
 */
@Composable
private fun EngineSection(container: AppContainer) {
    val engine by container.engines.state.collectAsStateWithLifecycle()

    Column {
        SectionHeader(
            "Separation engine",
            when {
                engine.engine != SeparationEngine.REMOTE -> "passthrough — four copies, no separation"
                engine.health != null -> "htdemucs on ${engine.health?.device}, over the network"
                else -> "server — not reachable yet"
            },
        )

        EngineChoice(
            title = "Separation server",
            subtitle = "htdemucs on a real machine over Wi-Fi. The phone decodes and uploads, the " +
                "server sends four stems back.",
            selected = engine.engine == SeparationEngine.REMOTE,
            onSelect = { container.engines.select(SeparationEngine.REMOTE) },
        )
        EngineChoice(
            title = "Passthrough",
            subtitle = "Four quarter-volume copies of the mix. Instant, and musically useless — " +
                "it exists to prove the playback path.",
            selected = engine.engine == SeparationEngine.PASSTHROUGH,
            onSelect = { container.engines.select(SeparationEngine.PASSTHROUGH) },
        )

        if (engine.engine == SeparationEngine.REMOTE) {
            Spacer(Modifier.height(10.dp))
            ServerAddress(
                state = engine,
                onEdit = { container.engines.editServerUrl(it) },
                onCheck = { container.engines.saveServerUrl() },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Changing this does not touch stems that already exist — a track keeps whatever " +
                "engine made it until you run ⋮ → Separate again.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerAddress(
    state: EngineState,
    onEdit: (String) -> Unit,
    onCheck: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.20:8765") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCheck() }),
        )

        Spacer(Modifier.height(6.dp))
        val health = state.health
        val error = state.error
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            when {
                state.probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…", style = MaterialTheme.typography.labelSmall)
                }

                health != null -> Text(
                    "Connected · ${health.model} on ${health.device} · ${health.sampleRate} Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                else -> Text(
                    "Not checked yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCheck) { Text("Save & test") }
        }
    }
}

@Composable
private fun EngineChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TrackRow(
    entry: TrackWithStems,
    /** Index of the deck this track is loaded on, or -1. */
    loadedOn: Int,
    hasGrid: Boolean,
    onPlay: () -> Unit,
    onCue: () -> Unit,
    onAnalyse: () -> Unit,
    onRequeue: () -> Unit,
    onDelete: () -> Unit,
) {
    val track = entry.track
    val ready = track.separationState == SeparationState.READY
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (loadedOn >= 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = ready, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (loadedOn >= 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(track.artist, formatTime(track.durationMs)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            when (track.separationState) {
                SeparationState.READY -> Text(
                    if (loadedOn >= 0) "on deck ${PlayerController.deckName(loadedOn)}"
                    else "4 stems ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                SeparationState.PENDING -> Text(
                    "queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SeparationState.RUNNING -> Column {
                    Text(
                        "separating ${(track.separationProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { track.separationProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SeparationState.FAILED -> Text(
                    track.separationError ?: "separation failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Track options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Cue to the other deck") },
                    onClick = { menuOpen = false; onCue() },
                    enabled = ready,
                )
                DropdownMenuItem(
                    // Existing tracks were separated before there was a detector to run on them,
                    // and re-separating for a grid would be minutes of work to recover seconds of
                    // it. The drums are already on disk; this just reads them again.
                    text = { Text(if (hasGrid) "Re-analyse beat grid" else "Find beat grid") },
                    onClick = { menuOpen = false; onAnalyse() },
                    enabled = ready,
                )
                DropdownMenuItem(
                    text = { Text("Separate again") },
                    onClick = { menuOpen = false; onRequeue() },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun RecordingRow(recording: Recording) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LedPalette.LedRecording),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                recording.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "slot ${recording.slot + 1} · ${formatTime(recording.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EffectRow(effect: Effect, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            effect.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${effect.ordinal + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
