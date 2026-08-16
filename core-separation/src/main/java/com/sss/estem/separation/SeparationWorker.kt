package com.sss.estem.separation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sss.estem.audio.BeatDetector
import com.sss.estem.data.db.StemSet
import com.sss.estem.data.model.Stem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs one track's separation.
 *
 * It runs in the foreground because separation takes minutes, and a backgrounded process doing
 * minutes of sustained CPU is exactly what the platform kills first. The notification is not
 * decoration — without it the work does not survive the user leaving the app.
 */
class SeparationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope { runSeparation() }

    private suspend fun CoroutineScope.runSeparation(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L)
        if (trackId < 0) return Result.failure()

        if (!SeparationEnvironment.isInitialised) {
            Log.e(TAG, "Environment not installed; app process likely restarted mid-work")
            return Result.retry()
        }

        val library = SeparationEnvironment.library
        val storage = SeparationEnvironment.storage
        val separator = SeparationEnvironment.separator ?: return Result.retry()

        val track = library.track(trackId) ?: return Result.failure()

        return try {
            // On API 31+ this throws if the process is in a state that forbids starting a
            // foreground service. Losing the notification is survivable; failing the track is not.
            runCatching { setForeground(foregroundInfo(track.title, 0f)) }
            library.markSeparationRunning(trackId)

            val outputDir = storage.prepareStemDir(trackId)

            // The separator reports progress from a plain (non-suspending) callback, and both a
            // Room write and a notification update are far too expensive to do per tick. A
            // conflated flow lets the callback stay cheap while a single collector publishes at a
            // sane rate — and drops intermediate values rather than queueing them.
            val progress = MutableStateFlow(0f)
            val publisher = launchProgressPublisher(trackId, track.title, progress)

            val result = try {
                separator.separate(
                    source = Uri.parse(track.sourceUri),
                    outputDir = outputDir,
                    onProgress = { progress.value = it },
                )
            } finally {
                publisher.cancel()
            }

            when (result) {
                is SeparationResult.Success -> {
                    val output = result.output

                    // Only worth doing here, and only possible here: the drums have just been
                    // pulled out of the mix, and a beat tracker handed an isolated drum track does
                    // not have to guess which onsets are percussive. Seconds of work against
                    // minutes of separation, so it rides along rather than getting its own pass.
                    val grid = BeatDetector.analyse(
                        drums = output.files[Stem.DRUMS.ordinal],
                        sampleRate = output.sampleRate,
                        channelCount = output.channelCount,
                    )

                    library.markSeparationComplete(
                        StemSet(
                            trackId = trackId,
                            vocalsPath = output.files[Stem.VOCALS.ordinal].absolutePath,
                            drumsPath = output.files[Stem.DRUMS.ordinal].absolutePath,
                            bassPath = output.files[Stem.BASS.ordinal].absolutePath,
                            melodyPath = output.files[Stem.MELODY.ordinal].absolutePath,
                            sampleRate = output.sampleRate,
                            channelCount = output.channelCount,
                            frameCount = output.frameCount,
                            createdAt = System.currentTimeMillis(),
                            bpm = grid?.bpm ?: 0f,
                            firstBeatFrame = grid?.firstBeatFrame ?: 0L,
                        ),
                    )
                    Result.success()
                }

                is SeparationResult.Failure -> {
                    Log.w(TAG, "Separation failed for $trackId: ${result.reason}", result.cause)
                    storage.deleteStems(trackId)
                    library.markSeparationFailed(trackId, result.reason)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Separation crashed for $trackId", e)
            storage.deleteStems(trackId)
            library.markSeparationFailed(trackId, e.message ?: "Separation crashed")
            Result.failure()
        }
    }

    private fun CoroutineScope.launchProgressPublisher(
        trackId: Long,
        title: String,
        progress: StateFlow<Float>,
    ): Job = launch {
        var lastPercent = -1
        while (isActive) {
            val percent = (progress.value * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                SeparationEnvironment.library.updateSeparationProgress(trackId, progress.value)
                runCatching { setForeground(foregroundInfo(title, progress.value)) }
            }
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Separating", 0f)

    private fun foregroundInfo(title: String, progress: Float): ForegroundInfo {
        ensureChannel()
        val percent = (progress * 100).toInt()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Separating stems")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, progress <= 0f)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stem separation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Progress while a track is being split into stems" },
        )
    }

    companion object {
        private const val TAG = "SeparationWorker"
        private const val CHANNEL_ID = "separation"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_INTERVAL_MS = 250L
        const val KEY_TRACK_ID = "trackId"
    }
}
