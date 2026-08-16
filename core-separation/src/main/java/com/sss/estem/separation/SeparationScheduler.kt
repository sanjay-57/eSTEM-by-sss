package com.sss.estem.separation

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Queues separation work.
 *
 * Work is unique per track and serialised into one chain: running two htdemucs inferences at once
 * on a phone is slower overall than running them back to back, and doubles peak memory at the
 * point where it is already the binding constraint.
 */
class SeparationScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(trackId: Long) {
        val request = OneTimeWorkRequestBuilder<SeparationWorker>()
            .setInputData(Data.Builder().putLong(SeparationWorker.KEY_TRACK_ID, trackId).build())
            .setConstraints(
                Constraints.Builder()
                    // Sustained multi-minute CPU on a nearly-flat battery is a bad trade; the
                    // user's track will still be there when it is charged a little.
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(TAG_SEPARATION)
            .build()

        workManager.enqueueUniqueWork(workName(trackId), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueAll(trackIds: List<Long>) = trackIds.forEach(::enqueue)

    fun cancel(trackId: Long) {
        workManager.cancelUniqueWork(workName(trackId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_SEPARATION)
    }

    private fun workName(trackId: Long) = "$WORK_PREFIX$trackId"

    companion object {
        private const val WORK_PREFIX = "separate-"
        const val TAG_SEPARATION = "separation"
    }
}
