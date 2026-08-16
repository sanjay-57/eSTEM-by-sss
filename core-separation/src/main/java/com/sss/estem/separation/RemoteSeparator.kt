package com.sss.estem.separation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sss.estem.audio.PcmDecoder
import com.sss.estem.data.model.Stem
import com.sss.estem.data.storage.StemStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** What `GET /health` says about the machine on the other end. */
data class ServerHealth(val model: String, val device: String, val sampleRate: Int)

/**
 * Separation on a real machine, over the local network.
 *
 * htdemucs needs a fixed 7.8-second segment and several gigabytes to run one, which a phone does
 * not have — the on-device attempt was killed by the OOM killer partway through every track. This
 * moves the model to hardware that can hold it and leaves the phone doing what it is good at.
 *
 * The phone uploads **decoded PCM, not the original file**. It already decodes with MediaCodec for
 * its own cache, so sending that removes every format question from the server: no ffmpeg, no
 * container parsing, and no codec that might decode differently at each end. The response is the
 * same format, so a downloaded stem is written to the cache byte for byte with no conversion.
 *
 * Stems are addressed **by name**. [Stem.fileName] already matches the server's vocabulary
 * (`vocals`, `drums`, `bass`, `other`), so the mapping is a lookup rather than an index — which
 * is the one part of this that used to be able to go wrong in silence.
 */
class RemoteSeparator(
    private val context: Context,
    private val settings: ServerSettings,
) : StemSeparator {

    override val displayName: String = "Server (htdemucs)"

    override suspend fun isReady(): Boolean = settings.resolved() != null

    /** One round trip, for the settings UI to say whether the address is any good. */
    suspend fun health(baseUrl: String): Result<ServerHealth> = withContext(Dispatchers.IO) {
        val base = ServerSettings.normalise(baseUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Not a usable address"))
        runCatching {
            val body = get("$base/health", readTimeout = 8_000)
            val json = JSONObject(body)
            check(json.optBoolean("ok")) { "Server did not report ok" }
            ServerHealth(
                model = json.optString("model", "unknown"),
                device = json.optString("device", "unknown"),
                sampleRate = json.optInt("sampleRate", 44100),
            )
        }
    }

    override suspend fun separate(
        source: Uri,
        outputDir: File,
        onProgress: (Float) -> Unit,
    ): SeparationResult = withContext(Dispatchers.IO) {
        val base = settings.resolved()
            ?: return@withContext SeparationResult.Failure("No separation server is configured")

        val scratch = File(outputDir, SCRATCH_NAME)
        var job: String? = null

        try {
            val info = PcmDecoder(context).decodeToFile(source, scratch) { progress ->
                onProgress(progress * DECODE_SHARE)
            } ?: return@withContext SeparationResult.Failure("Could not decode source audio")

            if (info.frameCount <= 0L) {
                return@withContext SeparationResult.Failure("Source decoded to zero frames")
            }

            job = upload(base, scratch, info.sampleRate, info.channelCount) { progress ->
                onProgress(DECODE_SHARE + progress * (SEPARATE_START - DECODE_SHARE))
            }
            scratch.delete()

            val finished = await(base, job) { progress ->
                onProgress(SEPARATE_START + progress * (FETCH_START - SEPARATE_START))
            }

            val frames = finished.optLong("frames")
            val sampleRate = finished.optInt("sampleRate")
            val channels = finished.optInt("channels")
            if (frames <= 0L || sampleRate <= 0 || channels <= 0) {
                return@withContext SeparationResult.Failure("Server returned no usable stem format")
            }

            val files = fetchStems(base, job, finished, outputDir, frames, channels) { progress ->
                onProgress(FETCH_START + progress * (1f - FETCH_START))
            }

            onProgress(1f)
            SeparationResult.Success(
                SeparationOutput(
                    files = files,
                    sampleRate = sampleRate,
                    channelCount = channels,
                    frameCount = frames,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Remote separation failed", e)
            SeparationResult.Failure(e.message ?: "The separation server could not be reached", e)
        } finally {
            scratch.delete()
            // Best effort: the server drops stale jobs on restart anyway, and failing to tidy up
            // must not turn a finished separation into a failed one.
            job?.let { id -> runCatching { request("$base/jobs/$id", "DELETE") } }
        }
    }

    // ---- protocol ----

    private suspend fun upload(
        base: String,
        pcm: File,
        sampleRate: Int,
        channels: Int,
        onProgress: (Float) -> Unit,
    ): String {
        val connection = (URL("$base/separate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = UPLOAD_TIMEOUT
            // Streaming mode keeps a 42 MB body out of the heap; without it HttpURLConnection
            // buffers the whole request before sending a byte of it.
            setFixedLengthStreamingMode(pcm.length())
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Sample-Rate", sampleRate.toString())
            setRequestProperty("X-Channels", channels.toString())
        }

        try {
            val total = pcm.length().coerceAtLeast(1L)
            var sent = 0L
            connection.outputStream.use { output ->
                FileInputStream(pcm).use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress(sent.toFloat() / total)
                    }
                }
            }

            val code = connection.responseCode
            val body = connection.readBody(code)
            if (code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_OK) {
                error(explain(code, body))
            }
            return JSONObject(body).getString("job")
        } finally {
            connection.disconnect()
        }
    }

    /** Polls until the job leaves the queue, and returns its final status document. */
    private suspend fun await(
        base: String,
        job: String,
        onProgress: (Float) -> Unit,
    ): JSONObject {
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            val status = try {
                failures = 0
                JSONObject(get("$base/jobs/$job", readTimeout = POLL_TIMEOUT))
            } catch (e: Exception) {
                // A long separation outlives a Wi-Fi hiccup; the job is still running on the other
                // side, so give up on the connection rather than on the track.
                if (++failures > MAX_POLL_FAILURES) throw e
                Log.w(TAG, "Poll $failures/$MAX_POLL_FAILURES failed", e)
                delay(POLL_INTERVAL_MS * failures)
                continue
            }

            when (status.optString("state")) {
                "done" -> return status
                "failed" -> error(status.optString("error").ifBlank { "Separation failed" })
                else -> onProgress(status.optDouble("progress", 0.0).toFloat())
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun fetchStems(
        base: String,
        job: String,
        status: JSONObject,
        outputDir: File,
        frames: Long,
        channels: Int,
        onProgress: (Float) -> Unit,
    ): List<File> {
        val names = status.optJSONArray("stems")
            ?: error("Server listed no stems")
        val expectedBytes = frames * channels * 2

        val written = mutableMapOf<Stem, File>()
        for (index in 0 until names.length()) {
            coroutineContext.ensureActive()
            val name = names.getString(index)
            val stem = Stem.ORDER.firstOrNull { it.fileName == name }
                ?: error("Server sent an unknown stem '$name'")

            val target = File(outputDir, "${stem.fileName}${StemStorage.STEM_EXTENSION}")
            download("$base/jobs/$job/stems/$name", target) { fraction ->
                onProgress((index + fraction) / names.length())
            }

            if (target.length() != expectedBytes) {
                error("Stem '$name' is ${target.length()} bytes, expected $expectedBytes")
            }
            written[stem] = target
        }

        // Anything missing here would leave a stem file from a previous run in place and play it
        // alongside three new ones, which is a subtle and very confusing result.
        val missing = Stem.ORDER.filterNot { it in written }
        if (missing.isNotEmpty()) {
            error("Server did not send ${missing.joinToString { it.fileName }}")
        }
        return Stem.ORDER.map { written.getValue(it) }
    }

    private suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = DOWNLOAD_TIMEOUT
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error(explain(code, connection.readBody(code)))
            }
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---- plumbing ----

    private fun get(url: String, readTimeout: Int): String = request(url, "GET", readTimeout)

    private fun request(url: String, method: String, readTimeout: Int = POLL_TIMEOUT): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            this.readTimeout = readTimeout
        }
        try {
            val code = connection.responseCode
            val body = connection.readBody(code)
            if (code !in 200..299) error(explain(code, body))
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.readBody(code: Int): String {
        val stream: InputStream? = if (code in 200..299) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    /** Turns the server's `{"error": ...}` into the sentence the track row will show. */
    private fun explain(code: Int, body: String): String {
        val reason = runCatching { JSONObject(body).optString("error") }.getOrNull()
        return if (reason.isNullOrBlank()) "Server returned HTTP $code" else reason
    }

    private companion object {
        const val TAG = "RemoteSeparator"
        const val BUFFER = 1 shl 16
        const val SCRATCH_NAME = "source.pcm.tmp"

        const val CONNECT_TIMEOUT = 15_000
        const val POLL_TIMEOUT = 20_000
        const val UPLOAD_TIMEOUT = 120_000
        const val DOWNLOAD_TIMEOUT = 120_000
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_POLL_FAILURES = 10

        /** Decoding is quick; the separation itself is the part measured in minutes. */
        const val DECODE_SHARE = 0.08f
        const val SEPARATE_START = 0.18f
        const val FETCH_START = 0.88f
    }
}
