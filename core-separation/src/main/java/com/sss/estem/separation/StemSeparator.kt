package com.sss.estem.separation

import android.net.Uri
import java.io.File

/**
 * The four stem files a successful separation produced, plus the format they share. All four are
 * headerless interleaved 16-bit PCM at [sampleRate] — the format [com.sss.estem.audio.StemPlayerEngine]
 * memory-maps.
 */
data class SeparationOutput(
    val files: List<File>,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
)

sealed interface SeparationResult {
    data class Success(val output: SeparationOutput) : SeparationResult
    data class Failure(val reason: String, val cause: Throwable? = null) : SeparationResult
}

/**
 * Splits a track into four stems.
 *
 * The seam exists because separation has two very different implementations with the same
 * contract: [PassthroughSeparator], which produces four identical copies and is what proves the
 * playback path is correct, and the htdemucs ONNX implementation, which is slow, large and
 * downloads a model. The rest of the app is written against this interface and does not care
 * which one is installed.
 */
interface StemSeparator {

    /** Human-readable name shown in settings so it is never ambiguous which engine ran. */
    val displayName: String

    /** False when a required asset (e.g. the model file) has not been downloaded yet. */
    suspend fun isReady(): Boolean

    /**
     * @param outputDir already emptied by the caller.
     * @param onProgress 0f..1f, called often enough to animate a bar but not per-frame.
     */
    suspend fun separate(
        source: Uri,
        outputDir: File,
        onProgress: (Float) -> Unit,
    ): SeparationResult
}
