package com.sss.estem.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.sss.estem.data.db.EstemDatabase
import com.sss.estem.data.db.Recording
import com.sss.estem.data.db.StemSet
import com.sss.estem.data.db.Track
import com.sss.estem.data.db.TrackWithStems
import com.sss.estem.data.importer.AudioProbe
import com.sss.estem.data.model.SeparationState
import com.sss.estem.data.storage.StemStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface ImportOutcome {
    data class Added(val trackId: Long, val title: String) : ImportOutcome
    data class Duplicate(val trackId: Long, val title: String) : ImportOutcome
    data class Rejected(val uri: Uri, val reason: String) : ImportOutcome
}

/**
 * Single entry point for the library. Owns the DB, the SAF permission grants and the stem cache
 * on disk, so those three can never disagree about what exists.
 */
class LibraryRepository(
    private val context: Context,
    private val db: EstemDatabase,
    private val storage: StemStorage,
    private val probe: AudioProbe,
) {

    fun observeLibrary(): Flow<List<TrackWithStems>> = db.trackDao().observeAll()

    fun observeTrack(id: Long): Flow<TrackWithStems?> = db.trackDao().observeWithStems(id)

    fun observeRecordings(): Flow<List<Recording>> = db.recordingDao().observeAll()

    suspend fun track(id: Long): Track? = db.trackDao().byId(id)

    suspend fun stems(trackId: Long): StemSet? = db.stemSetDao().byTrack(trackId)

    /**
     * Clears RUNNING rows left behind if the process died mid-separation, then re-checks that
     * every READY track's files are actually still on disk. Call once at app start.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        db.trackDao().resetStalledSeparations()
        db.trackDao().byState(listOf(SeparationState.READY)).forEach { track ->
            if (!storage.stemsExist(track.id)) {
                Log.w(TAG, "Stems missing on disk for track ${track.id}, re-queuing")
                db.stemSetDao().deleteForTrack(track.id)
                db.trackDao().setSeparationState(track.id, SeparationState.PENDING, 0f, null)
            }
        }
    }

    suspend fun import(uris: List<Uri>): List<ImportOutcome> = withContext(Dispatchers.IO) {
        uris.map { uri -> importOne(uri) }
    }

    private suspend fun importOne(uri: Uri): ImportOutcome {
        // Without this the URI stops resolving after a reboot and the track silently dies.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "No persistable permission for $uri", it) }

        db.trackDao().bySourceUri(uri.toString())?.let {
            return ImportOutcome.Duplicate(it.id, it.title)
        }

        val info = probe.probe(uri)
            ?: return ImportOutcome.Rejected(uri, "No decodable audio track")

        val track = Track(
            sourceUri = uri.toString(),
            title = info.title,
            artist = info.artist,
            album = info.album,
            durationMs = info.durationMs,
            sampleRate = info.sampleRate,
            channelCount = info.channelCount,
            mimeType = info.mimeType,
            separationState = SeparationState.PENDING,
            addedAt = System.currentTimeMillis(),
        )
        val id = db.trackDao().insert(track)
        if (id <= 0) {
            // Lost a race against a concurrent import of the same file.
            val existing = db.trackDao().bySourceUri(uri.toString())
                ?: return ImportOutcome.Rejected(uri, "Insert failed")
            return ImportOutcome.Duplicate(existing.id, existing.title)
        }
        return ImportOutcome.Added(id, info.title)
    }

    suspend fun markSeparationRunning(trackId: Long) =
        db.trackDao().setSeparationState(trackId, SeparationState.RUNNING, 0f, null)

    suspend fun updateSeparationProgress(trackId: Long, progress: Float) =
        db.trackDao().setProgress(trackId, progress.coerceIn(0f, 1f))

    suspend fun markSeparationFailed(trackId: Long, reason: String) =
        db.trackDao().setSeparationState(trackId, SeparationState.FAILED, 0f, reason)

    suspend fun markSeparationComplete(stemSet: StemSet) = withContext(Dispatchers.IO) {
        db.stemSetDao().upsert(stemSet)
        db.trackDao().setSeparationState(stemSet.trackId, SeparationState.READY, 1f, null)
    }

    /** @param bpm 0 to record that the track has no usable pulse. */
    suspend fun updateBeatGrid(trackId: Long, bpm: Float, firstBeatFrame: Long) =
        withContext(Dispatchers.IO) {
            db.stemSetDao().setBeatGrid(trackId, bpm, firstBeatFrame)
        }

    suspend fun requeue(trackId: Long) = withContext(Dispatchers.IO) {
        storage.deleteStems(trackId)
        db.stemSetDao().deleteForTrack(trackId)
        db.trackDao().setSeparationState(trackId, SeparationState.PENDING, 0f, null)
    }

    suspend fun pendingTracks(): List<Track> =
        db.trackDao().byState(listOf(SeparationState.PENDING))

    suspend fun delete(trackId: Long) = withContext(Dispatchers.IO) {
        val track = db.trackDao().byId(trackId)
        storage.deleteStems(trackId)
        db.trackDao().delete(trackId)
        track?.let { releasePermission(Uri.parse(it.sourceUri)) }
    }

    private fun releasePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** The hardware keeps exactly four recordings; the fifth pushes out the oldest. */
    suspend fun addRecording(
        path: String,
        title: String,
        durationMs: Long,
        sourceTrackId: Long?,
    ): Recording = withContext(Dispatchers.IO) {
        val dao = db.recordingDao()
        if (dao.count() >= MAX_RECORDINGS) {
            dao.oldest()?.let { stale ->
                runCatching { java.io.File(stale.path).delete() }
                dao.delete(stale.id)
            }
        }
        // Re-read after the eviction above so the slot it freed is visible.
        val occupied = dao.occupiedSlots().toSet()
        val slot = (0 until MAX_RECORDINGS).firstOrNull { it !in occupied } ?: 0
        val recording = Recording(
            slot = slot,
            path = path,
            sourceTrackId = sourceTrackId,
            title = title,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(recording)
        recording.copy(id = id)
    }

    fun stemCacheBytes(): Long = storage.stemCacheBytes()

    companion object {
        private const val TAG = "LibraryRepository"
        const val MAX_RECORDINGS = 4
        val ACCEPTED_MIME_TYPES: Array<String> = AudioProbe.ACCEPTED_MIME_TYPES
    }
}
