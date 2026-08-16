package com.sss.estem.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The detector runs against a synthetic drum track rather than a fixture, so the answer is known
 * exactly and the test carries no audio with it.
 *
 * A separated drums stem is what this sees in production — isolated percussive hits over near
 * silence — which is close to what these clicks are, and is the reason the detector can get away
 * with a broadband energy onset function instead of an FFT.
 */
class BeatDetectorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `finds the tempo of a steady click track`() {
        val bpm = 128.0
        val firstBeat = 5_000L
        val file = writeClickTrack(bpm = bpm, firstBeatFrame = firstBeat, seconds = 30.0)

        val grid = BeatDetector.analyse(file, SAMPLE_RATE, CHANNELS)

        assertNotNull("detector found no pulse in a click track", grid)
        grid!!
        assertEquals(bpm, grid.bpm.toDouble(), 1.0)
        assertTrue("confidence was ${grid.confidence}", grid.confidence > 0.5f)
    }

    @Test
    fun `puts the grid on the beat, not between beats`() {
        val bpm = 100.0
        val firstBeat = 12_345L
        val file = writeClickTrack(bpm = bpm, firstBeatFrame = firstBeat, seconds = 30.0)

        val grid = BeatDetector.analyse(file, SAMPLE_RATE, CHANNELS)
        assertNotNull(grid)
        grid!!

        // Phase is only meaningful modulo one beat: which beat it locked onto is arbitrary, being
        // on one of them is the whole point. A grid a half-beat out scores just as well on tempo
        // and puts every loop point off the beat, so this is the assertion that matters.
        val period = grid.framesPerBeat
        val offBy = ((grid.firstBeatFrame - firstBeat).toDouble().mod(period))
            .let { minOf(it, period - it) }

        assertTrue(
            "grid sits ${offBy.roundToInt()} frames off the beat (period ${period.roundToInt()})",
            offBy < ONSET_TOLERANCE_FRAMES,
        )
    }

    /**
     * A detector that always answers is worse than one that admits it does not know, because loops
     * snap to whatever it says. These three are the material that used to get a confident tempo:
     *
     * - a bare tone, whose energy per hop drifts as the hop grid slides through its cycle, which
     *   is a periodic wobble with nothing musical in it;
     * - a swelling pad, the realistic version of the same thing — tonal bleed in a drums stem;
     * - white noise, which has no structure at all and still beat the confidence test, because the
     *   best of a few thousand candidate grids beats their average on any material whatsoever.
     *
     * All three are rejected on peakiness: separated drums leave a nearly empty onset envelope with
     * tall spikes, and none of these do.
     */
    @Test
    fun `reports nothing for material with no transients`() {
        assertNull("bare tone", BeatDetector.analyse(tone(), SAMPLE_RATE, CHANNELS))
        assertNull("swelling pad", BeatDetector.analyse(pad(), SAMPLE_RATE, CHANNELS))
        assertNull("white noise", BeatDetector.analyse(noise(), SAMPLE_RATE, CHANNELS))
    }

    private fun tone(): File = synth("tone.pcm") { f ->
        sin(2.0 * PI * 220.0 * f / SAMPLE_RATE) * 8_000
    }

    private fun pad(): File = synth("pad.pcm") { f ->
        val swell = 0.6 + 0.4 * sin(2.0 * PI * 0.15 * f / SAMPLE_RATE)
        3_000 * swell * (
            sin(2.0 * PI * 110.0 * f / SAMPLE_RATE) +
                sin(2.0 * PI * 164.8 * f / SAMPLE_RATE) +
                sin(2.0 * PI * 220.5 * f / SAMPLE_RATE)
            )
    }

    private fun noise(): File {
        val random = java.util.Random(SEED)
        return synth("noise.pcm") { random.nextGaussian() * 4_000 }
    }

    private fun synth(name: String, sample: (Int) -> Double): File {
        val frames = SAMPLE_RATE * 20
        val pcm = ShortArray(frames * CHANNELS)
        for (f in 0 until frames) {
            val value = sample(f).coerceIn(-32_000.0, 32_000.0).toInt().toShort()
            pcm[f * CHANNELS] = value
            pcm[f * CHANNELS + 1] = value
        }
        return folder.newFile(name).apply { writeBytes(pcm.toLittleEndianBytes()) }
    }

    @Test
    fun `snapping rounds to the nearest line and flooring stays behind the cursor`() {
        val grid = BeatGrid(bpm = 120f, firstBeatFrame = 0L, sampleRate = 48_000)
        val beat = grid.framesPerBeat.roundToLong() // 24_000 frames at 120 bpm
        assertEquals(24_000L, beat)

        // Just past beat three: nearest beat is three, nearest bar is the one at beat four.
        val cursor = beat * 3 + 100

        assertEquals(beat * 3, grid.snapToBeat(cursor))
        assertEquals(beat * 4, grid.snapToBar(cursor))
        assertEquals(beat * 3, grid.floorToBeat(cursor))
        // Flooring must never land ahead of the cursor — a loop that starts in front of the
        // playhead throws it to the far end of the region the instant it engages.
        assertEquals(0L, grid.floorToBar(cursor))
        assertTrue(grid.floorToBar(cursor) <= cursor)

        assertEquals(beat * 4, grid.framesForBeats(4))
    }

    /** Exponentially decaying noise bursts on the beat, over silence. */
    private fun writeClickTrack(bpm: Double, firstBeatFrame: Long, seconds: Double): File {
        val frames = (SAMPLE_RATE * seconds).toInt()
        val pcm = ShortArray(frames * CHANNELS)
        val framesPerBeat = SAMPLE_RATE * 60.0 / bpm
        val random = java.util.Random(SEED)

        var beat = 0
        while (true) {
            val at = (firstBeatFrame + beat * framesPerBeat).toInt()
            if (at >= frames) break
            beat++
            for (i in 0 until CLICK_FRAMES) {
                val f = at + i
                if (f >= frames) break
                val envelope = exp(-i / CLICK_DECAY_FRAMES)
                val value = (random.nextGaussian() * 9_000 * envelope)
                    .coerceIn(-32_000.0, 32_000.0).toInt().toShort()
                pcm[f * CHANNELS] = value
                pcm[f * CHANNELS + 1] = value
            }
        }

        return folder.newFile("clicks-${bpm.toInt()}.pcm").apply {
            writeBytes(pcm.toLittleEndianBytes())
        }
    }

    /** Headerless interleaved little-endian int16 — the stem cache's own format. */
    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in indices) {
            bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((this[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun Double.mod(divisor: Double): Double {
        val remainder = this % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val SEED = 20260816L
        const val CLICK_FRAMES = 2_000
        const val CLICK_DECAY_FRAMES = 300.0

        /**
         * Two envelope hops. The onset function resolves to one hop, and the phase sweep quantises
         * within a beat, so landing inside a couple of hops is as exact as the design allows.
         */
        const val ONSET_TOLERANCE_FRAMES = 700.0
    }
}
