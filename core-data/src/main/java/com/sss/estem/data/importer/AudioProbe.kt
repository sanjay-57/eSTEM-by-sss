package com.sss.estem.data.importer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/** What we can learn about a file before deciding whether to accept it into the library. */
data class ProbeResult(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String?,
)

/**
 * Reads tags and stream parameters straight off a SAF URI. Everything here relies on platform
 * decoders — Android has had native FLAC, MP3, AAC and WAV support since API 27, which is why
 * minSdk is 27 and why no codec is bundled.
 */
class AudioProbe(private val context: Context) {

    fun probe(uri: Uri): ProbeResult? {
        val display = displayName(uri)
        val retriever = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Tag read failed for $uri", e)
        } finally {
            runCatching { retriever.release() }
        }

        val stream = probeStream(uri) ?: return null

        return ProbeResult(
            title = title?.takeIf { it.isNotBlank() } ?: display?.substringBeforeLast('.') ?: "Untitled",
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            sampleRate = stream.sampleRate,
            channelCount = stream.channelCount,
            mimeType = stream.mime,
        )
    }

    private data class StreamInfo(val sampleRate: Int, val channelCount: Int, val mime: String?)

    /** Returns null when the file has no track the platform can decode as audio. */
    private fun probeStream(uri: Uri): StreamInfo? {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                if (pfd == null) return null
                extractor.setDataSource(pfd.fileDescriptor)
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                return StreamInfo(
                    sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100,
                    channelCount = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2,
                    mime = mime,
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Stream probe failed for $uri", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val TAG = "AudioProbe"

        /** Offered in the SAF picker. Anything the platform decoders handle will work. */
        val ACCEPTED_MIME_TYPES = arrayOf(
            "audio/flac",
            "audio/x-flac",
            "audio/mpeg",
            "audio/wav",
            "audio/x-wav",
            "audio/aac",
            "audio/mp4",
            "audio/aiff",
            "audio/x-aiff",
            "audio/ogg",
        )
    }
}
