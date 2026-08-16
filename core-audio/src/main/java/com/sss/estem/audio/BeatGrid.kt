package com.sss.estem.audio

import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Where the beats fall in a track, in frames.
 *
 * Frames rather than milliseconds throughout. A loop is set by snapping to this grid and then
 * handed to the engine, which counts frames; rounding a bar boundary out to milliseconds and back
 * costs up to half a millisecond each way, and a loop that is half a millisecond short drifts a
 * noticeable amount over a minute of repeats.
 */
data class BeatGrid(
    val bpm: Float,
    /** The first detected beat. Everything else is this plus a whole number of [framesPerBeat]. */
    val firstBeatFrame: Long,
    val sampleRate: Int,
    /** 0f..1f. Low means the track has no steady pulse the detector could find, not that it is slow. */
    val confidence: Float = 1f,
) {

    val valid: Boolean get() = bpm > 0f && sampleRate > 0

    val framesPerBeat: Double
        get() = if (!valid) 0.0 else sampleRate.toDouble() * 60.0 / bpm.toDouble()

    /** Frames spanned by a whole number of beats — a loop length. */
    fun framesForBeats(beats: Int): Long =
        if (!valid) 0L else (framesPerBeat * beats).roundToLong()

    /** The beat line nearest `frame` — where a point the user is placing deliberately belongs. */
    fun snapToBeat(frame: Long): Long = snap(frame, 1, round = true)

    /**
     * The bar line nearest `frame`.
     *
     * Bars, not beats, are what a loop wants by default: a one-beat loop of a four-beat pattern
     * repeats the same quarter of it, which is a stutter, not a loop.
     */
    fun snapToBar(frame: Long, beatsPerBar: Int = DEFAULT_BEATS_PER_BAR): Long =
        snap(frame, beatsPerBar, round = true)

    /**
     * The last grid line at or before `frame`.
     *
     * What "loop from here" wants, as against [snapToBeat]. Rounding to the nearest line can land
     * ahead of the cursor, and a loop whose start is in front of the playhead throws the cursor
     * forward to the end of the region the moment it engages.
     */
    fun floorToBeat(frame: Long): Long = snap(frame, 1, round = false)

    fun floorToBar(frame: Long, beatsPerBar: Int = DEFAULT_BEATS_PER_BAR): Long =
        snap(frame, beatsPerBar, round = false)

    private fun snap(frame: Long, beats: Int, round: Boolean): Long {
        if (!valid) return frame
        val span = framesPerBeat * beats
        val offset = (frame - firstBeatFrame) / span
        val units = if (round) offset.roundToLong() else floor(offset).toLong()
        return (firstBeatFrame + units * span).roundToLong().coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_BEATS_PER_BAR = 4

        fun none(sampleRate: Int) = BeatGrid(0f, 0L, sampleRate, 0f)
    }
}

/**
 * Finds the pulse in a separated drums stem.
 *
 * Beat trackers normally open with a spectral flux detector, because in a full mix the only way to
 * tell a kick from a bass note is which frequencies moved. Here the drums arrive on their own — the
 * separation has already done the hard part of the job — so the onset function can be plain
 * broadband energy flux, which needs no FFT and reads the file once.
 *
 * Two passes over that envelope: an autocorrelation to find roughly how far apart the beats are,
 * then a comb search around that answer for the period *and* phase that best line up with the
 * onsets. The second pass is what produces a usable grid — autocorrelation alone gives a spacing
 * with no idea where the downbeat is, and a loop needs to start on one.
 */
object BeatDetector {

    /**
     * @param drums headerless interleaved int16 PCM, as written into the stem cache.
     * @return null when nothing steady enough was found. A wrong grid is worse than none: loops
     * snap to it, so a bad estimate turns every loop into a stumble, whereas no grid just means
     * loops fall where the finger puts them.
     */
    fun analyse(drums: File, sampleRate: Int, channelCount: Int): BeatGrid? {
        if (!drums.isFile || sampleRate <= 0 || channelCount <= 0) return null

        val hop = max(64, sampleRate / HOPS_PER_SECOND)
        val envelope = onsetEnvelope(drums, hop, channelCount) ?: return null
        if (envelope.size < MIN_ENVELOPE_HOPS) return null

        // Before asking how fast the beats are, ask whether there are any.
        val peakiness = peakiness(envelope)
        if (peakiness < MIN_PEAKINESS) {
            Log.i(TAG, "${drums.name}: no transients to track (peakiness " +
                "${"%.1f".format(peakiness)})")
            return null
        }

        val hopsPerSecond = sampleRate.toDouble() / hop
        val coarse = coarsePeriod(envelope, hopsPerSecond) ?: return null
        val best = refine(envelope, coarse, hopsPerSecond) ?: return null

        val bpm = (60.0 * hopsPerSecond / best.period).toFloat()
        if (best.confidence < MIN_CONFIDENCE) {
            Log.i(TAG, "no steady pulse in ${drums.name} (best ${"%.1f".format(bpm)} bpm, " +
                "confidence ${"%.2f".format(best.confidence)})")
            return null
        }

        val grid = BeatGrid(
            bpm = bpm,
            firstBeatFrame = (best.phase * hop).roundToLong(),
            sampleRate = sampleRate,
            confidence = best.confidence,
        )
        Log.i(TAG, "${drums.name}: ${"%.2f".format(bpm)} bpm, first beat at " +
            "${grid.firstBeatFrame} frames, confidence ${"%.2f".format(best.confidence)}")
        return grid
    }

    /**
     * Half-wave rectified difference of per-hop RMS: how much louder this hop is than the last,
     * and zero when it is quieter. Rises sharply on a hit and ignores a decaying tail, which is
     * what makes the peaks line up with where a drum was struck rather than where it was loudest.
     */
    private fun onsetEnvelope(file: File, hop: Int, channelCount: Int): FloatArray? {
        val samplesPerHop = hop * channelCount
        val flux = ArrayList<Float>(1 shl 14)
        val buffer = ByteArray(READ_CHUNK_BYTES)

        var previous = 0.0
        var accumulated = 0.0
        var counted = 0
        var carryLow = -1

        runCatching {
            FileInputStream(file).use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break

                    var i = 0
                    // A chunk boundary can split a sample; the low byte is carried across it.
                    if (carryLow >= 0 && read > 0) {
                        val value = ((buffer[0].toInt() shl 8) or carryLow).toShort().toInt()
                        accumulated += value.toDouble() * value
                        counted++
                        carryLow = -1
                        i = 1
                    }
                    while (i + 1 < read) {
                        val value = ((buffer[i].toInt() and 0xFF) or
                            (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                        accumulated += value.toDouble() * value
                        counted++
                        i += 2

                        if (counted == samplesPerHop) {
                            val rms = sqrt(accumulated / samplesPerHop) / Short.MAX_VALUE
                            // Log domain, so a quiet passage's onsets count as much as a loud
                            // one's — a linear envelope lets the chorus decide the whole tempo.
                            val level = ln(1.0 + LOG_KNEE * rms)
                            flux.add(max(0.0, level - previous).toFloat())
                            previous = level
                            accumulated = 0.0
                            counted = 0
                        }
                    }
                    if (i < read) carryLow = buffer[i].toInt() and 0xFF
                }
            }
        }.onFailure {
            Log.w(TAG, "could not read ${file.name}", it)
            return null
        }

        return if (flux.size < MIN_ENVELOPE_HOPS) null else flux.toFloatArray()
    }

    /**
     * How spiky the onset envelope is — the mean of its loudest few per cent against its overall
     * mean. Exposed for the test that sets the threshold.
     *
     * Percussion leaves a nearly empty envelope with tall spikes on the hits. Sustained material
     * leaves a low one that wobbles continuously, because a steady tone's energy per hop drifts as
     * the hop grid slides through its cycle. A wobble has a period, and the comb search will lock
     * onto it perfectly happily and report a confident tempo for a stem with no percussion in it
     * at all — so peakiness is asked first, and it is a different question from how fast.
     */
    private fun peakiness(envelope: FloatArray): Float {
        if (envelope.isEmpty()) return 0f
        val mean = envelope.average().toFloat()
        if (mean <= 0f) return 0f

        val sorted = envelope.copyOf()
        sorted.sort()
        val from = (sorted.size * TOP_FRACTION).toInt().coerceIn(0, sorted.size - 1)
        var sum = 0.0
        for (i in from until sorted.size) sum += sorted[i]
        return ((sum / (sorted.size - from)) / mean).toFloat()
    }

    /** Autocorrelation peak inside the plausible tempo range, in hops. */
    private fun coarsePeriod(envelope: FloatArray, hopsPerSecond: Double): Double? {
        val minLag = (hopsPerSecond * 60.0 / MAX_BPM).toInt().coerceAtLeast(2)
        val maxLag = (hopsPerSecond * 60.0 / MIN_BPM).toInt()
        if (maxLag <= minLag || maxLag >= envelope.size / 2) return null

        var bestLag = -1
        var bestScore = Float.NEGATIVE_INFINITY
        val scores = FloatArray(maxLag + 1)

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in lag until envelope.size) sum += envelope[i] * envelope[i - lag]
            // Divided by the overlap length, or long lags lose to short ones purely on term count.
            val score = (sum / (envelope.size - lag)).toFloat() * tempoPrior(60.0 * hopsPerSecond / lag)
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null

        // Parabolic interpolation through the peak and its neighbours. One hop is over a beat per
        // minute of error at these lags, and a grid that is a bpm out walks off the beat inside
        // twenty bars.
        if (bestLag > minLag && bestLag < maxLag) {
            val left = scores[bestLag - 1]
            val centre = scores[bestLag]
            val right = scores[bestLag + 1]
            val denominator = left - 2f * centre + right
            if (denominator != 0f) {
                val offset = 0.5f * (left - right) / denominator
                if (offset > -1f && offset < 1f) return bestLag + offset.toDouble()
            }
        }
        return bestLag.toDouble()
    }

    private data class Fit(val period: Double, val phase: Double, val confidence: Float)

    /**
     * Sweeps period and phase together around the autocorrelation's answer.
     *
     * Autocorrelation says how far apart the onsets are; it says nothing about where they start,
     * and being right about the spacing while wrong about the phase puts every loop point exactly
     * off the beat. Scoring an actual pulse train against the envelope settles both at once.
     */
    private fun refine(envelope: FloatArray, coarse: Double, hopsPerSecond: Double): Fit? {
        val mean = envelope.average().toFloat()
        if (mean <= 0f) return null

        var best: Fit? = null
        var bestScore = Float.NEGATIVE_INFINITY

        var step = -PERIOD_SWEEP_STEPS
        while (step <= PERIOD_SWEEP_STEPS) {
            val period = coarse * (1.0 + step * PERIOD_SWEEP_FRACTION)
            step++
            if (period < 2.0 || period * MIN_BEATS >= envelope.size) continue

            val prior = tempoPrior(60.0 * hopsPerSecond / period)
            val phaseSteps = min(MAX_PHASE_STEPS, period.toInt().coerceAtLeast(1))

            var strongestPhase = 0.0
            var strongest = Float.NEGATIVE_INFINITY
            var coverageAtPeak = 0f
            var acrossPhases = 0.0
            var scored = 0

            for (phaseStep in 0 until phaseSteps) {
                val phase = period * phaseStep / phaseSteps
                var sum = 0.0
                var landed = 0
                var hits = 0
                var at = phase
                while (at < envelope.size) {
                    // Peak of the hop and its neighbours rather than the single hop the arithmetic
                    // points at. The onset function resolves to one hop and the phase sweep
                    // quantises inside a beat, so an onset half a hop either way is the normal
                    // case, not the exception.
                    val centre = at.toInt()
                    var peak = envelope[centre]
                    if (centre > 0) peak = max(peak, envelope[centre - 1])
                    if (centre + 1 < envelope.size) peak = max(peak, envelope[centre + 1])

                    sum += peak
                    if (peak > mean) landed++
                    hits++
                    at += period
                }
                if (hits < MIN_BEATS) continue

                val strength = (sum / hits).toFloat()
                acrossPhases += strength
                scored++
                if (strength > strongest) {
                    strongest = strength
                    strongestPhase = phase
                    // What fraction of the predicted beats actually have an onset on them. One
                    // loud transient in an otherwise empty track gives a huge average against a
                    // near-zero mean; a pulse is beats landing *repeatedly*.
                    coverageAtPeak = landed.toFloat() / hits
                }
            }

            if (scored == 0 || strongest <= 0f) continue
            val meanAcrossPhases = (acrossPhases / scored).toFloat()
            if (meanAcrossPhases <= 0f) continue

            val score = strongest * prior * coverageAtPeak
            if (score > bestScore) {
                bestScore = score
                // Confidence is how far the winning phase stands above the *other phases of the
                // same period* — not above the envelope's mean.
                //
                // Against the mean, dense material scores brilliantly for the wrong reason: take
                // the strongest of three neighbouring hops a few hundred times and the average of
                // those maxima sits well above the overall average whatever the material is, so
                // white noise came out at 0.92 confident. Every phase does equally well on noise,
                // and exactly one does well on a beat — which is the thing a grid is claiming.
                val contrast = strongest / meanAcrossPhases
                val confidence =
                    (((contrast - 1f) / CONTRAST_SPAN) * coverageAtPeak).coerceIn(0f, 1f)
                best = Fit(period, strongestPhase, confidence)
            }
        }
        return best
    }

    /**
     * Weight toward ordinary tempi, so a grid at half or double the real speed loses.
     *
     * A comb at half tempo hits every other beat and scores exactly as well per hit, so nothing in
     * the signal itself distinguishes the two. Log-normal around 120, which is the usual shape for
     * this and lets 70 and 180 through while pushing 45 and 300 down.
     */
    private fun tempoPrior(bpm: Double): Float {
        if (bpm <= 0.0) return 0f
        val octaves = ln(bpm / PRIOR_CENTRE_BPM)
        return exp(-0.5 * (octaves / PRIOR_WIDTH) * (octaves / PRIOR_WIDTH)).toFloat()
    }

    private const val TAG = "estem.beats"

    /** ~172 envelope points a second at 44.1 kHz, so a hop is under 6 ms. */
    private const val HOPS_PER_SECOND = 172
    private const val READ_CHUNK_BYTES = 1 shl 18
    private const val LOG_KNEE = 40.0

    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0
    private const val PRIOR_CENTRE_BPM = 120.0
    private const val PRIOR_WIDTH = 0.55

    private const val PERIOD_SWEEP_STEPS = 12
    private const val PERIOD_SWEEP_FRACTION = 0.0025
    private const val MAX_PHASE_STEPS = 96
    private const val MIN_BEATS = 16
    private const val MIN_ENVELOPE_HOPS = 512

    /** A winning phase this much stronger than the average phase counts as fully confident. */
    private const val CONTRAST_SPAN = 0.6f

    /**
     * Floor on [Fit.confidence]. Deliberately low.
     *
     * Phase contrast turns out to be a weak test on its own, because the winner is the best of a
     * few thousand candidates and the best of a few thousand draws beats their average even on
     * noise. Measured across eight separated drum stems it ranged 0.25 to 0.92, while white noise
     * scored 1.0 — so it cannot be the thing that decides whether material has a beat. It is kept
     * as a floor against genuinely unmetred percussion, and [MIN_PEAKINESS] does the real work.
     */
    private const val MIN_CONFIDENCE = 0.15f

    private const val TOP_FRACTION = 0.95f

    /**
     * Set from measurement rather than taste.
     *
     * Across the eight separated drum stems on hand, peakiness ran 8.6 to 16.6. White noise came
     * out at 5.4, a swelling three-oscillator pad at 5.0 and a bare sine at 3.4. Seven sits in the
     * gap with room either side.
     */
    private const val MIN_PEAKINESS = 7.0f
}
