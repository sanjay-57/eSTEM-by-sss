package com.sss.estem.data.storage

import android.content.Context
import com.sss.estem.data.model.Stem
import java.io.File

/**
 * Everything the app writes lives in internal storage, so nothing needs runtime permissions and
 * uninstalling cleans up after itself.
 *
 * ```
 * filesDir/
 *   stems/<trackId>/{vocals,drums,bass,other}.pcm
 *   recordings/<uuid>.wav
 *   models/htdemucs.onnx
 * ```
 */
class StemStorage(private val context: Context) {

    private val root: File get() = context.filesDir

    val stemsRoot: File get() = File(root, "stems").apply { mkdirs() }
    val recordingsRoot: File get() = File(root, "recordings").apply { mkdirs() }
    val modelsRoot: File get() = File(root, "models").apply { mkdirs() }

    fun stemDir(trackId: Long): File = File(stemsRoot, trackId.toString())

    fun stemFile(trackId: Long, stem: Stem): File =
        File(stemDir(trackId), "${stem.fileName}$STEM_EXTENSION")

    fun stemsExist(trackId: Long): Boolean =
        Stem.ORDER.all { stemFile(trackId, it).let { f -> f.isFile && f.length() > 0 } }

    fun prepareStemDir(trackId: Long): File = stemDir(trackId).apply {
        deleteRecursively()
        mkdirs()
    }

    fun deleteStems(trackId: Long) {
        stemDir(trackId).deleteRecursively()
    }

    fun recordingFile(name: String): File = File(recordingsRoot, "$name.wav")

    fun modelFile(name: String): File = File(modelsRoot, name)

    /** Total bytes the stem cache is currently occupying, for the settings screen. */
    fun stemCacheBytes(): Long =
        stemsRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /**
         * Stems are stored as headerless interleaved 16-bit PCM at the track's own sample rate.
         * Raw costs disk but removes a decode step from load, which keeps track switching instant.
         * Swap to FLAC here if the cache size becomes a problem.
         */
        const val STEM_EXTENSION = ".pcm"
    }
}
