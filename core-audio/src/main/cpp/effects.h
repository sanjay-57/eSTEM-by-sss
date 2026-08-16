#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

#include "ramp.h"

namespace estem {

/**
 * A unit vector rotated by a fixed angle per frame — an LFO without a transcendental in it.
 *
 * The chorus and tremolo used to call `std::sin` once per frame, and the chorus once per frame
 * *per channel*, which puts a transcendental in the render callback at audio rate. Rotating a
 * vector costs four multiplies and yields sine and cosine at once, and the rotation constants only
 * have to be recomputed when the rate changes — once a block at worst.
 *
 * Changing the rate here also cannot click: the vector keeps its current angle and simply starts
 * turning at a new speed, where recomputing `sin(phase)` from a rate-scaled phase would jump.
 */
struct Phasor {
    /** cos(phase) and sin(phase) of the current angle. */
    float re = 1.0f;
    float im = 0.0f;

    void setRate(float hz, int32_t sampleRate) {
        const float angle = 6.28318530718f * hz / static_cast<float>(sampleRate);
        cosInc_ = std::cos(angle);
        sinInc_ = std::sin(angle);
    }

    inline void advance() {
        const float rotated = re * cosInc_ - im * sinInc_;
        im = re * sinInc_ + im * cosInc_;
        re = rotated;
    }

    /**
     * Call once per block. Repeated rotation accumulates rounding error, and left alone the vector
     * slowly spirals in until the LFO fades out entirely.
     */
    inline void renormalise() {
        const float magnitude = std::sqrt(re * re + im * im);
        if (magnitude > 1e-6f) {
            re /= magnitude;
            im /= magnitude;
        }
    }

    void reset() {
        re = 1.0f;
        im = 0.0f;
    }

private:
    float cosInc_ = 1.0f;
    float sinInc_ = 0.0f;
};

/**
 * The eight effects the hardware exposes, in the order they appear on the vertical axis of its
 * effects menu. The ordinal is what the UI sends, so do not reorder.
 */
enum class EffectId : int32_t {
    None = -1,
    Reverb = 0,
    Delay = 1,
    LowPass = 2,
    HighPass = 3,
    BitCrush = 4,
    Chorus = 5,
    Tremolo = 6,
    Stutter = 7,
};

constexpr int kEffectCount = 8;

/**
 * A single-slot effects rack sitting after the deck mix.
 *
 * The hardware's effects menu is two axes: vertical picks one of eight, horizontal sets how hard
 * it hits, from nothing to maximum. So there is exactly one effect active at a time and exactly
 * one continuous parameter — this class matches that rather than being a general effects graph.
 *
 * All buffers are sized once in [prepare]; nothing here allocates on the audio thread.
 */
class EffectRack {
public:
    /** Worker thread only — allocates. */
    void prepare(int32_t sampleRate);

    /** Safe from any thread. */
    void setEffect(EffectId id);
    void setIntensity(float intensity);
    EffectId effect() const { return static_cast<EffectId>(current_.load(std::memory_order_relaxed)); }
    float intensity() const { return intensity_.target(); }

    /** Drops the active effect and silences its tails. Safe from any thread. */
    void clear();

    /** Audio thread only. Processes stereo interleaved in place. */
    void process(float* buffer, int32_t numFrames);

private:
    void resetState();

    void processReverb(float* buffer, int32_t numFrames);
    void processDelay(float* buffer, int32_t numFrames);
    void processFilter(float* buffer, int32_t numFrames, bool highPass);
    void processBitCrush(float* buffer, int32_t numFrames);
    void processChorus(float* buffer, int32_t numFrames);
    void processTremolo(float* buffer, int32_t numFrames);
    void processStutter(float* buffer, int32_t numFrames);

    int32_t sampleRate_ = 48000;

    std::atomic<int32_t> current_{static_cast<int32_t>(EffectId::None)};
    /** Ramped so sweeping the horizontal axis does not zipper. */
    Ramp intensity_;
    /** Set when the effect changes so the audio thread flushes stale tails once. */
    std::atomic<bool> resetPending_{false};

    // --- Schroeder reverb: four parallel combs into two series allpasses, per channel ---
    static constexpr int kCombCount = 4;
    static constexpr int kAllpassCount = 2;
    std::vector<float> comb_[2][kCombCount];
    int combIndex_[2][kCombCount] = {};
    float combStore_[2][kCombCount] = {};
    std::vector<float> allpass_[2][kAllpassCount];
    int allpassIndex_[2][kAllpassCount] = {};

    // --- delay / chorus share a long line each ---
    std::vector<float> delayLine_[2];
    int delayIndex_[2] = {};
    /**
     * Delay time in frames, slewed and read fractionally.
     *
     * Deriving an integer tap from the intensity every frame is what made sweeping the delay
     * zipper: the read pointer walks a whole sample at a time and each step is a discontinuity,
     * fed straight back into the line. A glided fractional tap sweeps continuously instead.
     */
    float delayFrames_ = 0.0f;
    std::vector<float> chorusLine_[2];
    int chorusIndex_[2] = {};
    Phasor chorusLfo_;

    // --- one-pole filters ---
    float filterState_[2] = {};

    // --- bitcrush sample-and-hold ---
    float crushHold_[2] = {};
    float crushCounter_ = 0.0f;

    // --- tremolo ---
    Phasor tremoloLfo_;

    // --- stutter: captures a slice then loops it ---
    std::vector<float> stutterBuffer_;
    int stutterWrite_ = 0;
    int stutterRead_ = 0;
    int stutterLength_ = 0;
    bool stutterCapturing_ = true;
    /** Frames of fade at each end of a slice, so the loop point does not click. */
    int stutterFadeFrames_ = 1;
};

} // namespace estem
