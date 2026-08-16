package com.sss.estem.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class PcmInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
) {
    val durationMs: Long get() = if (sampleRate > 0) frameCount * 1000 / sampleRate else 0
}

/**
 * Decodes any format the platform can read into headerless interleaved 16-bit PCM.
 *
 * This is the only decode in the app. Playback reads the resulting .pcm files by mmap, so nothing
 * is decoded at load time and switching tracks is instant; the separation worker consumes the
 * same output. Both paths therefore agree on sample rate and frame count by construction.
 */
class PcmDecoder(private val context: Context) {

    /**
     * Decodes a SAF document.
     *
     * @param gain applied while writing. Real stems sum back to the original mix, so anything
     * standing in for them has to be scaled to preserve that property — four unity copies would
     * sum to 4x and clip.
     */
    fun decodeToFile(
        source: Uri,
        destination: File,
        gain: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): PcmInfo? = decode(destination, gain, onProgress) { extractor ->
        context.contentResolver.openFileDescriptor(source, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
            true
        } ?: false
    }

    /** Decodes a file already on disk. */
    fun decodeToFile(
        source: File,
        destination: File,
        gain: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): PcmInfo? = decode(destination, gain, onProgress) { extractor ->
        extractor.setDataSource(source.absolutePath)
        true
    }

    private fun decode(
        destination: File,
        gain: Float,
        onProgress: ((Float) -> Unit)?,
        setSource: (MediaExtractor) -> Boolean,
    ): PcmInfo? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            if (!setSource(extractor)) return null

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val totalDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            destination.parentFile?.mkdirs()
            var sampleRate = inputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channelCount = inputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = inputFormat.getIntOr(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
            var bytesWritten = 0L

            BufferedOutputStream(FileOutputStream(destination), BUFFER_BYTES).use { out ->
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var lastReportedProgress = -1

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, size, extractor.sampleTime, 0,
                                )
                                if (totalDurationUs > 0) {
                                    val pct =
                                        (extractor.sampleTime * 100 / totalDurationUs).toInt()
                                    if (pct != lastReportedProgress) {
                                        lastReportedProgress = pct
                                        onProgress?.invoke(pct / 100f)
                                    }
                                }
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Some decoders only report the real parameters here, not on the
                            // input format, so this is the value we trust.
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channelCount =
                                outputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                            pcmEncoding =
                                outputFormat.getIntOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> {
                            if (outputIndex >= 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    bytesWritten += writePcm16(outputBuffer, pcmEncoding, gain, out)
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawOutputEos = true
                                }
                            }
                        }
                    }
                }
            }

            onProgress?.invoke(1f)
            val bytesPerFrame = channelCount * 2
            return PcmInfo(
                sampleRate = sampleRate,
                channelCount = channelCount,
                frameCount = if (bytesPerFrame > 0) bytesWritten / bytesPerFrame else 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            destination.delete()
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Normalises whatever the decoder produced down to 16-bit. Lossless sources commonly come out
     * as 24- or 32-bit; storing those verbatim would triple the stem cache for headroom the
     * hardware never had.
     */
    private fun writePcm16(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        gain: Float,
        out: BufferedOutputStream,
    ): Long {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val scratch = ByteBuffer.allocate(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)

        fun emit(sample: Float) {
            val scaled = (sample * gain).coerceIn(-1f, 1f)
            scratch.putShort((scaled * 32767f).roundToInt().toShort())
        }

        when (pcmEncoding) {
            ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.hasRemaining()) emit(floats.get())
            }

            ENCODING_PCM_24BIT_PACKED -> {
                while (buffer.remaining() >= 3) {
                    // Little-endian 24-bit; keep the top two bytes.
                    buffer.get()
                    val mid = buffer.get().toInt() and 0xFF
                    val high = buffer.get().toInt()
                    emit((((high shl 8) or mid).toShort()).toFloat() / 32768f)
                }
            }

            ENCODING_PCM_32BIT -> {
                val ints = buffer.asIntBuffer()
                while (ints.hasRemaining()) emit((ints.get() shr 16).toFloat() / 32768f)
            }

            else -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.hasRemaining()) emit(shorts.get().toFloat() / 32768f)
            }
        }

        scratch.flip()
        val length = scratch.remaining()
        out.write(scratch.array(), 0, length)
        return length.toLong()
    }

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        private const val TAG = "PcmDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val BUFFER_BYTES = 1 shl 16

        // AudioFormat constants, repeated here so this class does not depend on AudioFormat.
        private const val ENCODING_PCM_16BIT = 2
        private const val ENCODING_PCM_FLOAT = 4
        private const val ENCODING_PCM_24BIT_PACKED = 21
        private const val ENCODING_PCM_32BIT = 22
    }
}
