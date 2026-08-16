#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string>

#include "ramp.h"
#include "stems.h"
#include "stretcher.h"

namespace estem {

/**
 * One song's four stems plus a single shared playback cursor.
 *
 * The cursor being shared is the whole point: four independently-clocked players drift apart
 * within seconds and comb-filter, which is exactly the artefact the hardware never has. Here the
 * stems cannot drift because there is only one position in existence.
 *
 * Stems are memory-mapped headerless interleaved int16 PCM, all at the same rate and channel
 * count. mmap means loading a track allocates nothing and costs no decode — the kernel pages
 * samples in as the cursor reaches them, so switching tracks is instant even for long files.
 *
 * Phase 2 instantiates a second one of these for two-song mixing; nothing here assumes it is
 * the only deck.
 */
class StemDeck {
public:
    StemDeck() = default;
    ~StemDeck();

    StemDeck(const StemDeck&) = delete;
    StemDeck& operator=(const StemDeck&) = delete;

    /**
     * Maps four .pcm files. Call from a worker thread, never the audio thread — it does file I/O.
     * Returns false and leaves the deck unloaded if any file is missing or inconsistent.
     */
    bool load(const std::string paths[kStemCount], int32_t sampleRate, int32_t channelCount);

    void unload();

    void setEngineSampleRate(int32_t sampleRate);

    // ---- transport (safe from any thread) ----
    void setPlaying(bool playing) { playing_.store(playing, std::memory_order_relaxed); }
    bool isPlaying() const { return playing_.load(std::memory_order_relaxed); }
    void seekFrames(int64_t frame);
    int64_t positionFrames() const { return cursor_.load(std::memory_order_relaxed); }
    int64_t totalFrames() const { return frameCount_.load(std::memory_order_relaxed); }
    bool isLoaded() const { return loaded_.load(std::memory_order_acquire); }
    int32_t sampleRate() const { return sampleRate_; }
    int32_t channelCount() const { return channelCount_; }

    /**
     * Playback rate, where 1 is the track's own speed.
     *
     * Negative runs the cursor backwards, which is what makes a drag a real scrub rather than a
     * burst of seeks. Ramped, so a finger changing direction does not step the rate.
     *
     * This is the *cursor* rate: on its own it repitches, like a turntable. [setStretch] is what
     * separates tempo from pitch.
     */
    void setRate(float rate);
    float rate() const;

    /**
     * Whether [setRate] changes tempo (true) or pitch as well (false).
     *
     * On, the deck reads through a WSOLA stretcher, so a track can be pulled to another's tempo
     * without the vocal moving with it. Off, the cursor is simply read faster or slower, which is
     * a turntable — right for a scrub, and right when the pitch shift *is* the effect.
     *
     * Ignored while the rate is negative. Reverse is always a turntable: overlap-adding grains
     * backwards is not a scrub, and pitch dropping as the drag slows is the thing that makes a
     * scrub read as one.
     */
    void setStretchEnabled(bool enabled) {
        stretchWanted_.store(enabled, std::memory_order_relaxed);
    }
    bool stretchEnabled() const { return stretchWanted_.load(std::memory_order_relaxed); }

    /**
     * A region to loop between, in frames. Pass (-1, -1) for the whole track, which is what
     * looping meant before there were loop points.
     */
    void setLoopRegion(int64_t startFrame, int64_t endFrame);
    int64_t loopStart() const { return loopStart_.load(std::memory_order_relaxed); }
    int64_t loopEnd() const { return loopEnd_.load(std::memory_order_relaxed); }

    /**
     * This deck's share of the mix, 0f..1f. The crossfader, seen from the deck's side.
     *
     * Separate from the stem gains because it is not one: the sliders are what the performer set
     * and a crossfade must leave them exactly as it found them, in the same way isolate does.
     */
    void setOutputGain(float gain) { output_.setTarget(std::clamp(gain, 0.0f, 1.0f)); }
    float outputGain() const { return output_.target(); }
    /** Jumps the output gain rather than gliding to it. For deciding where a deck starts. */
    void primeOutputGain(float gain) { output_.reset(std::clamp(gain, 0.0f, 1.0f)); }

    // ---- mixing controls (safe from any thread) ----
    /** 0f..1f, ramped. */
    void setStemGain(int stem, float gain);
    float stemGain(int stem) const;

    /**
     * Bitmask of isolated stems. Non-zero means only the set stems are audible — this mirrors
     * press-and-hold-outer-edge on the hardware, where isolating is a separate state from volume
     * and releasing it restores whatever the sliders were set to.
     */
    void setIsolateMask(uint32_t mask) { isolateMask_.store(mask, std::memory_order_relaxed); }
    uint32_t isolateMask() const { return isolateMask_.load(std::memory_order_relaxed); }

    /** Bitmask of muted stems, independent of isolate. */
    void setMuteMask(uint32_t mask) { muteMask_.store(mask, std::memory_order_relaxed); }
    uint32_t muteMask() const { return muteMask_.load(std::memory_order_relaxed); }

    void setLooping(bool looping) { looping_.store(looping, std::memory_order_relaxed); }

    /**
     * Smoothed magnitude of one stem's own material, 0f..1f, updated every render block.
     *
     * Deliberately measured *before* the slider gain: the lit LED count already shows where the
     * slider is, so what the lights should pulse with is whether that stem has anything going on
     * right now. A stem turned down to one LED still flickers on that LED when it plays.
     */
    float stemEnergy(int stem) const;

    /**
     * Audio thread only. Sums the four stems into `out` (stereo interleaved float, additive) and
     * advances the cursor. Writes nothing if the deck is unloaded, paused or mid-swap.
     */
    void render(float* out, int32_t numFrames);

private:
    struct Mapping {
        void* base = nullptr;      // page-aligned mmap base, for munmap
        size_t length = 0;         // mmap length
        const int16_t* samples = nullptr;
        int64_t frames = 0;
    };

    void unmapAll();

    /**
     * Four-point Catmull-Rom read at a fractional position, audio thread only.
     *
     * Every read goes through this, including the ordinary 1x case. Branching to an integer fast
     * path was the alternative and it is not worth it: the interpolation is a handful of multiplies
     * against four stems' worth of memory traffic that happens either way, and one read path means
     * one place where an out-of-range position has to be handled.
     */
    static void fetch(const Mapping& stem, int32_t channels, double position,
                      float& left, float& right);

    Mapping stems_[kStemCount];
    /** Slider position, written by the UI. */
    Ramp gains_[kStemCount];
    /**
     * Isolate/mute expressed as a gain so state changes fade instead of clicking. Target is
     * recomputed on the audio thread each block from the two masks; slower ramp than [gains_]
     * because it is a state change, not a slider move.
     */
    Ramp maskGains_[kStemCount];
    /**
     * Dips to zero across a seek and back up again. The cursor is what the audio thread is
     * reading from, so moving it mid-callback is a step discontinuity in the waveform — one
     * click per seek, which a finger dragging the scrub bar turns into a continuous buzz.
     */
    Ramp seekFade_;
    /** The frame a seek is waiting on, or -1. Applied by the audio thread once the fade is out. */
    std::atomic<int64_t> pendingSeek_{-1};
    /**
     * A position the audio thread must adopt without fading, or -1.
     *
     * Seeking while paused has nothing to fade and nothing to advance a fade with, so it cannot go
     * through [pendingSeek_] — but [readPos_] belongs to the audio thread, so it cannot simply be
     * written either. This is the handover, consumed at the top of a block whether or not the deck
     * is playing.
     */
    std::atomic<int64_t> resyncTo_{-1};
    /**
     * The read position, in fractional source frames. Audio thread only; [cursor_] is the copy
     * everyone else reads. Double rather than float because a float's mantissa runs out of
     * integer precision around 16 million frames, which is six minutes.
     */
    double readPos_ = 0.0;
    /** Cursor speed, 1 for the track's own. Ramped so a rate change cannot step. */
    Ramp rate_;
    /** This deck's share of the mix. Ramped, like every other gain in the path. */
    Ramp output_;

    TimeStretcher stretcher_;
    std::atomic<bool> stretchWanted_{false};
    /**
     * Crossfade between the two read paths: 0 is the cursor read directly, 1 is the stretcher.
     *
     * The stretcher's output runs about a grain behind the cursor, so the two paths are never in
     * phase and switching between them is a splice. Blending across a few milliseconds costs one
     * extra read while it is in motion and nothing at all once it settles, and it means engaging
     * the stretcher, and a scrub taking the rate negative, are both simply a target change.
     */
    Ramp stretchMix_;
    /** True while [stretcher_] is being advanced. Audio thread only. */
    bool stretchRunning_ = false;

    std::atomic<bool> loaded_{false};
    /**
     * Set while load()/unload() mutate the mappings. The audio thread renders silence rather
     * than touching a half-swapped deck — cheaper and safer than a lock it must never block on.
     */
    std::atomic<bool> swapping_{false};

    std::atomic<bool> playing_{false};
    std::atomic<bool> looping_{false};
    std::atomic<int64_t> cursor_{0};
    std::atomic<int64_t> frameCount_{0};
    std::atomic<uint32_t> isolateMask_{0};
    std::atomic<uint32_t> muteMask_{0};
    /** Loop region in frames; -1 means the whole track. */
    std::atomic<int64_t> loopStart_{-1};
    std::atomic<int64_t> loopEnd_{-1};
    /** Written by the audio thread, read by the UI. Relaxed: a stale frame is invisible. */
    std::atomic<float> energy_[kStemCount]{};

    int32_t sampleRate_ = 0;
    int32_t channelCount_ = 2;
    int32_t engineSampleRate_ = 48000;
};

} // namespace estem
