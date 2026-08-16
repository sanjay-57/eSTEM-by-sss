#include "stem_deck.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <thread>

#define LOG_TAG "estem.deck"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace estem {

namespace {
constexpr float kInt16Scale = 1.0f / 32768.0f;

/**
 * The audio thread bails out for at most this long while a swap happens. Two callback periods
 * at the worst realistic buffer size is plenty; the loader waits this out before mutating.
 */
constexpr auto kSwapSettleTime = std::chrono::milliseconds(20);

/**
 * Mean |sample| of ordinary music sits around 0.1–0.25, so the meter needs headroom scaling to
 * use the full range. Too much scaling and everything pins at 1.0 and the lights stop moving —
 * 3.0 keeps normal material mid-range and leaves peaks somewhere to go.
 */
constexpr float kEnergyScale = 3.0f;
constexpr float kEnergyAttack = 0.55f;
constexpr float kEnergyRelease = 0.08f;

/**
 * How long the rate takes to reach a new value, in milliseconds.
 *
 * Long enough that snapping from reverse to forward sounds like a hand letting go of a record
 * rather than a splice, short enough that a scrub still tracks the finger.
 */
constexpr float kRateRampMs = 40.0f;

/**
 * Rate is clamped rather than trusted. Beyond about 4x the cubic read is undersampling badly
 * enough to alias, and a scrub can generate an arbitrary rate from a fast flick.
 */
constexpr float kMaxRate = 4.0f;

/** How long the crossfade between the direct and stretched read paths takes. */
constexpr float kStretchBlendMs = 12.0f;

/** How long the deck's own output gain takes to reach a new crossfader position. */
constexpr float kCrossfadeRampMs = 20.0f;

/**
 * Catmull-Rom through p1 and p2, with p0 and p3 as their outer neighbours.
 *
 * Linear interpolation is fine at speeds near 1, but a scrub runs the cursor at whatever rate the
 * finger asks for, and linear's high-frequency loss varies with the fractional offset — so the
 * dullness it adds comes and goes during the drag, which is more noticeable than the loss itself.
 */
inline float cubic(float p0, float p1, float p2, float p3, float t) {
    const float a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
    const float b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    const float c = -0.5f * p0 + 0.5f * p2;
    return ((a * t + b) * t + c) * t + p1;
}
} // namespace

void StemDeck::fetch(const Mapping& stem, int32_t channels, double position,
                     float& left, float& right) {
    if (stem.samples == nullptr || stem.frames <= 0) {
        left = 0.0f;
        right = 0.0f;
        return;
    }

    const int64_t last = stem.frames - 1;
    int64_t i1 = static_cast<int64_t>(position);
    if (i1 < 0) i1 = 0;
    if (i1 > last) i1 = last;
    const float t = static_cast<float>(position - static_cast<double>(i1));

    // Clamped rather than wrapped at both ends: the four taps straddle the read point, so the very
    // first and last frames of a track would otherwise reach outside the mapping.
    const int64_t i0 = i1 > 0 ? i1 - 1 : 0;
    const int64_t i2 = i1 < last ? i1 + 1 : last;
    const int64_t i3 = i2 < last ? i2 + 1 : last;

    const int16_t* s = stem.samples;
    if (channels == 1) {
        const float mono = cubic(static_cast<float>(s[i0]) * kInt16Scale,
                                 static_cast<float>(s[i1]) * kInt16Scale,
                                 static_cast<float>(s[i2]) * kInt16Scale,
                                 static_cast<float>(s[i3]) * kInt16Scale, t);
        left = mono;
        right = mono;
        return;
    }

    left = cubic(static_cast<float>(s[i0 * 2]) * kInt16Scale,
                 static_cast<float>(s[i1 * 2]) * kInt16Scale,
                 static_cast<float>(s[i2 * 2]) * kInt16Scale,
                 static_cast<float>(s[i3 * 2]) * kInt16Scale, t);
    right = cubic(static_cast<float>(s[i0 * 2 + 1]) * kInt16Scale,
                  static_cast<float>(s[i1 * 2 + 1]) * kInt16Scale,
                  static_cast<float>(s[i2 * 2 + 1]) * kInt16Scale,
                  static_cast<float>(s[i3 * 2 + 1]) * kInt16Scale, t);
}

StemDeck::~StemDeck() {
    unmapAll();
}

void StemDeck::setEngineSampleRate(int32_t sampleRate) {
    engineSampleRate_ = sampleRate;
    for (auto& gain : gains_) gain.setSampleRate(sampleRate);
    for (auto& mask : maskGains_) mask.setSampleRate(sampleRate, 12.0f);
    seekFade_.setSampleRate(sampleRate, 3.0f);
    rate_.setSampleRate(sampleRate, kRateRampMs);
    stretchMix_.setSampleRate(sampleRate, kStretchBlendMs);
    // Slower than a slider: a crossfade is a move between two tracks, not a nudge, and 5 ms of it
    // would be a cut.
    output_.setSampleRate(sampleRate, kCrossfadeRampMs);
    stretcher_.prepare(sampleRate);
}

void StemDeck::setRate(float rate) {
    rate_.setTarget(std::clamp(rate, -kMaxRate, kMaxRate));
}

float StemDeck::rate() const {
    return rate_.target();
}

void StemDeck::setLoopRegion(int64_t startFrame, int64_t endFrame) {
    // Stored as given and validated on the audio thread against the current track. Doing it here
    // would race a load, and a loop region that outlives its track is the sort of thing that reads
    // past a mapping.
    loopStart_.store(startFrame, std::memory_order_relaxed);
    loopEnd_.store(endFrame, std::memory_order_relaxed);
}

bool StemDeck::load(const std::string paths[kStemCount], int32_t sampleRate, int32_t channelCount) {
    // Take the deck out of the render path, then give the audio thread time to notice.
    swapping_.store(true, std::memory_order_release);
    loaded_.store(false, std::memory_order_release);
    playing_.store(false, std::memory_order_relaxed);
    std::this_thread::sleep_for(kSwapSettleTime);

    unmapAll();

    bool ok = true;
    int64_t minFrames = INT64_MAX;
    const int64_t bytesPerFrame = static_cast<int64_t>(channelCount) * 2;

    for (int i = 0; i < kStemCount && ok; ++i) {
        const int fd = ::open(paths[i].c_str(), O_RDONLY);
        if (fd < 0) {
            LOGW("open failed: %s", paths[i].c_str());
            ok = false;
            break;
        }
        struct stat st {};
        if (::fstat(fd, &st) != 0 || st.st_size < bytesPerFrame) {
            LOGW("stat failed or file too small: %s", paths[i].c_str());
            ::close(fd);
            ok = false;
            break;
        }
        void* base = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (base == MAP_FAILED) {
            LOGW("mmap failed: %s", paths[i].c_str());
            ok = false;
            break;
        }
        // Ask the kernel to read ahead so the audio thread is unlikely to hit a hard page fault.
        ::madvise(base, static_cast<size_t>(st.st_size), MADV_WILLNEED);
        ::madvise(base, static_cast<size_t>(st.st_size), MADV_SEQUENTIAL);

        stems_[i].base = base;
        stems_[i].length = static_cast<size_t>(st.st_size);
        stems_[i].samples = static_cast<const int16_t*>(base);
        stems_[i].frames = st.st_size / bytesPerFrame;
        minFrames = std::min(minFrames, stems_[i].frames);
    }

    if (!ok) {
        unmapAll();
        swapping_.store(false, std::memory_order_release);
        return false;
    }

    // Separation can leave the four files a frame or two apart; the shortest one defines the
    // track so no stem ever reads past its own mapping.
    sampleRate_ = sampleRate;
    channelCount_ = channelCount;
    frameCount_.store(minFrames, std::memory_order_relaxed);
    cursor_.store(0, std::memory_order_relaxed);
    readPos_ = 0.0;
    pendingSeek_.store(-1, std::memory_order_relaxed);
    resyncTo_.store(-1, std::memory_order_relaxed);
    isolateMask_.store(0, std::memory_order_relaxed);
    muteMask_.store(0, std::memory_order_relaxed);
    // A loop region belongs to the track it was measured against, so a new track drops it.
    loopStart_.store(-1, std::memory_order_relaxed);
    loopEnd_.store(-1, std::memory_order_relaxed);

    rate_.setSampleRate(engineSampleRate_, kRateRampMs);
    rate_.reset(1.0f);
    stretchMix_.setSampleRate(engineSampleRate_, kStretchBlendMs);
    stretchMix_.reset(0.0f);
    stretchRunning_ = false;

    // Short: a seek should feel instant, and 3 ms is already well below what reads as a fade.
    // Starts at full — the deck would otherwise render silence until something moved it.
    seekFade_.setSampleRate(engineSampleRate_, 3.0f);
    seekFade_.reset(1.0f);

    for (int i = 0; i < kStemCount; ++i) {
        gains_[i].setSampleRate(engineSampleRate_);
        gains_[i].reset(1.0f);
        maskGains_[i].setSampleRate(engineSampleRate_, 12.0f);
        maskGains_[i].reset(1.0f);
        energy_[i].store(0.0f, std::memory_order_relaxed);
    }

    loaded_.store(true, std::memory_order_release);
    swapping_.store(false, std::memory_order_release);
    LOGI("loaded %lld frames @ %d Hz x%d ch", static_cast<long long>(minFrames), sampleRate,
         channelCount);
    return true;
}

void StemDeck::unload() {
    swapping_.store(true, std::memory_order_release);
    loaded_.store(false, std::memory_order_release);
    playing_.store(false, std::memory_order_relaxed);
    std::this_thread::sleep_for(kSwapSettleTime);
    unmapAll();
    frameCount_.store(0, std::memory_order_relaxed);
    cursor_.store(0, std::memory_order_relaxed);
    swapping_.store(false, std::memory_order_release);
}

void StemDeck::unmapAll() {
    for (auto& stem : stems_) {
        if (stem.base != nullptr) {
            ::munmap(stem.base, stem.length);
        }
        stem = Mapping{};
    }
}

void StemDeck::seekFrames(int64_t frame) {
    const int64_t total = frameCount_.load(std::memory_order_relaxed);
    const int64_t target = std::clamp<int64_t>(frame, 0, total);

    // Nothing is being rendered, so there is no discontinuity to hide — and nothing to advance the
    // fade either, so a deferred seek would sit pending until playback resumed.
    if (!playing_.load(std::memory_order_relaxed) || !loaded_.load(std::memory_order_acquire)) {
        pendingSeek_.store(-1, std::memory_order_relaxed);
        seekFade_.setTarget(1.0f);
        cursor_.store(target, std::memory_order_relaxed);
        // The read position is the audio thread's, even when that thread is doing nothing with it.
        resyncTo_.store(target, std::memory_order_relaxed);
        return;
    }

    // Hand it to the audio thread instead of moving the cursor under it. A later seek arriving
    // before the fade completes simply replaces this one, which is what dragging a scrub bar does.
    pendingSeek_.store(target, std::memory_order_relaxed);
    seekFade_.setTarget(0.0f);
}

void StemDeck::setStemGain(int stem, float gain) {
    if (stem < 0 || stem >= kStemCount) return;
    gains_[stem].setTarget(std::clamp(gain, 0.0f, 1.0f));
}

float StemDeck::stemGain(int stem) const {
    if (stem < 0 || stem >= kStemCount) return 0.0f;
    return gains_[stem].target();
}

void StemDeck::render(float* out, int32_t numFrames) {
    if (swapping_.load(std::memory_order_acquire)) return;
    if (!loaded_.load(std::memory_order_acquire)) return;

    // Consumed whether or not the deck is playing: seeking with playback stopped has no fade to
    // ride and no callback to advance it, so it hands the position over here instead.
    const int64_t resync = resyncTo_.exchange(-1, std::memory_order_relaxed);
    if (resync >= 0) readPos_ = static_cast<double>(resync);

    if (!playing_.load(std::memory_order_relaxed)) return;

    const int64_t total = frameCount_.load(std::memory_order_relaxed);
    if (total <= 0) return;

    const uint32_t isolate = isolateMask_.load(std::memory_order_relaxed);
    const uint32_t mute = muteMask_.load(std::memory_order_relaxed);
    const bool looping = looping_.load(std::memory_order_relaxed);
    const int32_t channels = channelCount_;

    // Resolved against *this* track every block, so a region left over from a longer one cannot
    // send the cursor past the end of a mapping.
    int64_t loopIn = loopStart_.load(std::memory_order_relaxed);
    int64_t loopOut = loopEnd_.load(std::memory_order_relaxed);
    if (loopIn < 0 || loopIn >= total) loopIn = 0;
    if (loopOut <= loopIn || loopOut > total) loopOut = total;
    const double regionIn = static_cast<double>(loopIn);
    const double regionOut = static_cast<double>(loopOut);
    const double regionSpan = regionOut - regionIn;

    StemSource source;
    for (int s = 0; s < kStemCount; ++s) source.samples[s] = stems_[s].samples;
    // The shortest stem, so a grain taken anywhere inside this is inside every mapping.
    source.frames = total;
    source.channels = channels;

    // Reverse is always a turntable, so the mode alone does not decide this — the rate does too.
    const bool wantStretch =
        stretchWanted_.load(std::memory_order_relaxed) && rate_.target() > 0.0f;
    if (wantStretch && !stretchRunning_) {
        stretcher_.reset(source, readPos_);
        stretchRunning_ = true;
    }
    stretchMix_.setTarget(wantStretch ? 1.0f : 0.0f);

    // Isolate and mute are ramped rather than switched, otherwise every press-and-hold on the
    // puck's outer edge produces a click.
    for (int s = 0; s < kStemCount; ++s) {
        const uint32_t bit = 1u << s;
        const bool audible = ((mute & bit) == 0) && (isolate == 0 || (isolate & bit) != 0);
        maskGains_[s].setTarget(audible ? 1.0f : 0.0f);
    }

    // Per-stem magnitude for the LEDs. Accumulated here because this is the only place the raw
    // samples are already in registers; measuring it anywhere else would mean reading the
    // mappings twice.
    float magnitude[kStemCount] = {};
    int32_t rendered = 0;

    for (int32_t f = 0; f < numFrames; ++f) {
        // Must advance every frame, like the other ramps, or it stalls mid-fade and the deck
        // stays silent. Once it reaches zero the waiting seek can be applied inaudibly.
        const float fade = seekFade_.next();
        if (fade <= 0.0f) {
            const int64_t pending = pendingSeek_.exchange(-1, std::memory_order_relaxed);
            if (pending >= 0) {
                readPos_ = static_cast<double>(pending);
                // The overlap buffer is still full of windowed audio from where the cursor was.
                if (stretchRunning_) stretcher_.reset(source, readPos_);
                seekFade_.setTarget(1.0f);
            }
        }

        // Advanced every frame whether or not it is being used, for the same reason as the gains.
        const float rate = rate_.next();

        // Both ends, because backwards is a real direction now: a scrub drags the cursor whichever
        // way the finger goes, and running off the front of a loop is as ordinary as running off
        // the back of one.
        if (readPos_ >= regionOut) {
            if (!looping) {
                playing_.store(false, std::memory_order_relaxed);
                break;
            }
            readPos_ -= regionSpan;
            if (readPos_ < regionIn || readPos_ >= regionOut) readPos_ = regionIn;
            // A wrap is a splice in the source, and the grains in flight belong to the far end of
            // the loop. Re-priming reads forward from the new position only, so the loop point is
            // as clean stretched as it is not.
            if (stretchRunning_) stretcher_.reset(source, readPos_);
        } else if (readPos_ < regionIn) {
            if (looping) {
                readPos_ += regionSpan;
                if (readPos_ < regionIn || readPos_ >= regionOut) readPos_ = regionOut - 1.0;
                if (stretchRunning_) stretcher_.reset(source, readPos_);
            } else {
                // Not a stop. Reaching the start in reverse is where a scrub naturally ends up,
                // and stopping playback there would mean the finger has to hand it back.
                readPos_ = regionIn;
            }
        }

        // Both read paths, blended. Once the mix settles only one of them runs, so the ordinary
        // case pays for exactly one.
        const float mix = stretchMix_.next();
        float stretchedL[kStemCount] = {};
        float stretchedR[kStemCount] = {};
        if (stretchRunning_) {
            stretcher_.next(source, readPos_, stretchedL, stretchedR);
        }

        float left = 0.0f;
        float right = 0.0f;

        for (int s = 0; s < kStemCount; ++s) {
            // Both ramps must advance every frame even when the stem is silent, or they stall
            // and the next un-mute jumps instead of fading.
            const float gain = gains_[s].next() * maskGains_[s].next();

            float sampleL = stretchedL[s];
            float sampleR = stretchedR[s];
            if (mix < 1.0f) {
                float directL = 0.0f;
                float directR = 0.0f;
                fetch(stems_[s], channels, readPos_, directL, directR);
                sampleL = directL + (sampleL - directL) * mix;
                sampleR = directR + (sampleR - directR) * mix;
            }

            // Pre-gain, so the lights show what the stem is doing, not how loud it is set.
            const float mono = 0.5f * (sampleL + sampleR);
            magnitude[s] += mono < 0.0f ? -mono : mono;

            if (gain <= 0.0f) continue;
            left += sampleL * gain;
            right += sampleR * gain;
        }

        // The crossfader, applied last so it scales the finished deck rather than any one stem.
        const float share = output_.next() * fade;
        out[f * 2] += left * share;
        out[f * 2 + 1] += right * share;
        readPos_ += static_cast<double>(rate);
        ++rendered;
    }

    cursor_.store(static_cast<int64_t>(readPos_), std::memory_order_relaxed);

    // Stop advancing the stretcher once nothing is reading from it — the grain search is the most
    // expensive thing in this callback and there is no reason to pay for it at 1x.
    if (!wantStretch && stretchMix_.settledAt(0.0f)) stretchRunning_ = false;

    if (rendered > 0) {
        const float inverse = 1.0f / static_cast<float>(rendered);
        for (int s = 0; s < kStemCount; ++s) {
            // Headroom-scaled so ordinary material reaches the top of the range, then a fast
            // attack and slow release — the same shape as a VU meter, which is what makes a
            // light look like it is following a beat rather than jittering.
            const float level = std::min(1.0f, magnitude[s] * inverse * kEnergyScale);
            const float previous = energy_[s].load(std::memory_order_relaxed);
            const float smoothed = level > previous
                ? previous + (level - previous) * kEnergyAttack
                : previous + (level - previous) * kEnergyRelease;
            energy_[s].store(smoothed, std::memory_order_relaxed);
        }
    }
}

float StemDeck::stemEnergy(int stem) const {
    if (stem < 0 || stem >= kStemCount) return 0.0f;
    return energy_[stem].load(std::memory_order_relaxed);
}

} // namespace estem
