package com.sss.estem.separation

import android.content.Context
import android.net.Uri
import com.sss.estem.audio.PcmDecoder
import com.sss.estem.data.model.Stem
import com.sss.estem.data.storage.StemStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes the source and writes the same PCM into all four stem slots.
 *
 * This is not a stand-in for the real thing musically — every slider moves the whole mix — but it
 * is exactly the signal needed to prove the playback path: four separate mmapped files summed
 * through one cursor must reconstruct the original with no comb filtering. Any phasing heard
 * here is drift in the engine, not a separation artefact, which makes it the cleanest possible
 * test of [com.sss.estem.audio.StemPlayerEngine].
 *
 * It also doubles as the fallback when the htdemucs model has not been downloaded yet, so a
 * freshly installed app can still play a track.
 */
class PassthroughSeparator(
    private val context: Context,
    private val storage: StemStorage,
) : StemSeparator {

    override val displayName: String = "Passthrough (no separation)"

    override suspend fun isReady(): Boolean = true

    override suspend fun separate(
        source: Uri,
        outputDir: File,
        onProgress: (Float) -> Unit,
    ): SeparationResult = withContext(Dispatchers.IO) {
        val decoder = PcmDecoder(context)
        val first = File(outputDir, "${Stem.ORDER.first().fileName}${StemStorage.STEM_EXTENSION}")

        // Decoding is ~85% of the work here; the copies are cheap.
        //
        // Each copy is written at 1/4 amplitude so that all four summed reconstruct the original,
        // which is the property real stems have. Writing four unity copies sums to 4x and clips
        // hard against the output stream.
        val info = decoder.decodeToFile(
            source = source,
            destination = first,
            gain = 1f / Stem.COUNT,
        ) { progress -> onProgress(progress * 0.85f) }
            ?: return@withContext SeparationResult.Failure("Could not decode source audio")

        if (info.frameCount <= 0) {
            return@withContext SeparationResult.Failure("Source decoded to zero frames")
        }

        val files = mutableListOf(first)
        Stem.ORDER.drop(1).forEachIndexed { index, stem ->
            val target = File(outputDir, "${stem.fileName}${StemStorage.STEM_EXTENSION}")
            first.copyTo(target, overwrite = true)
            files += target
            onProgress(0.85f + 0.15f * (index + 1) / (Stem.COUNT - 1))
        }

        onProgress(1f)
        SeparationResult.Success(
            SeparationOutput(
                files = files,
                sampleRate = info.sampleRate,
                channelCount = info.channelCount,
                frameCount = info.frameCount,
            ),
        )
    }
}
