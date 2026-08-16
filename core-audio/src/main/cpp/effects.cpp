#include "effects.h"

#include <algorithm>
#include <cmath>

namespace estem {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 2.0f * kPi;

// Classic Freeverb tuning, in frames at 44.1 kHz; scaled to the real rate in prepare().
constexpr int kCombTuning[4] = {1116, 1188, 1277, 1356};
constexpr int kAllpassTuning[2] = {556, 441};
// A few frames of offset between channels is what turns a mono tail into a stereo one.
constexpr int kStereoSpread = 23;

constexpr float kMaxDelaySeconds = 1.0f;
constexpr float kMaxChorusMillis = 30.0f;
constexpr float kMaxStutterSeconds = 0.5f;

constexpr float kDelayMinSeconds = 0.08f;
constexpr float kDelaySpanSeconds = 0.32f;
/**
 * How fast the delay *time* chases the intensity, per frame.
 *
 * Separate from the intensity ramp on purpose: a delay line's length is not a gain, and moving it
 * quickly repitches whatever is already in it. Slow enough that a full sweep glides like tape,
 * fast enough that the axis still feels connected to the finger.
 */
constexpr float kDelayGlide = 0.00008f;

/** The chorus LFO is fixed-rate, so its rotation constants are set once in prepare(). */
constexpr float kChorusRateHz = 0.6f;

/**
 * Fade at each end of a stutter slice.
 *
 * The slice loops by wrapping the read index, and the sample either side of that wrap has no
 * relationship to the other — a step discontinuity, so a click on every repeat. Stutter is meant
 * to sound chopped, not clicky, and 1.5 ms is short enough to leave the chop intact.
 */
constexpr float kStutterFadeMillis = 1.5f;

inline float softClip(float x) {
    // Keeps resonant/feedback effects from blowing up without a hard, buzzy clip.
    if (x > 1.0f) return 1.0f - 1.0f / (x + 1.0f) * 0.5f;
    if (x < -1.0f) return -1.0f + 1.0f / (-x + 1.0f) * 0.5f;
    return x;
}

inline int scaleToRate(int framesAt44k, int32_t sampleRate) {
    return std::max(1, static_cast<int>(static_cast<int64_t>(framesAt44k) * sampleRate / 44100));
}

/**
 * One-pole cutoff coefficient for a given position on the intensity axis.
 *
 * A `pow` inside an `exp`. Cheap once a block, expensive once a frame — which is what it used to
 * be, on both channels, and the sort of thing that turns a busy moment into an underrun.
 */
inline float onePoleCoeff(float amount, bool highPass, int32_t sampleRate) {
    // Sweep exponentially — a linear cutoff sweep sounds like it does nothing until the end.
    const float normalised = highPass ? amount : (1.0f - amount);
    const float cutoffHz = 40.0f * std::pow(400.0f, normalised);
    return std::clamp(
        1.0f - std::exp(-kTwoPi * cutoffHz / static_cast<float>(sampleRate)), 0.0f, 1.0f);
}

} // namespace

void EffectRack::prepare(int32_t sampleRate) {
    sampleRate_ = sampleRate;
    intensity_.setSampleRate(sampleRate, 25.0f);
    intensity_.reset(0.0f);

    for (int ch = 0; ch < 2; ++ch) {
        const int spread = ch * kStereoSpread;
        for (int i = 0; i < kCombCount; ++i) {
            comb_[ch][i].assign(scaleToRate(kCombTuning[i] + spread, sampleRate), 0.0f);
        }
        for (int i = 0; i < kAllpassCount; ++i) {
            allpass_[ch][i].assign(scaleToRate(kAllpassTuning[i] + spread, sampleRate), 0.0f);
        }
        delayLine_[ch].assign(static_cast<size_t>(kMaxDelaySeconds * sampleRate) + 1, 0.0f);
        chorusLine_[ch].assign(
            static_cast<size_t>(kMaxChorusMillis / 1000.0f * sampleRate) + 2, 0.0f);
    }
    // Stereo interleaved, so two floats per frame.
    stutterBuffer_.assign(static_cast<size_t>(kMaxStutterSeconds * sampleRate) * 2 + 2, 0.0f);

    chorusLfo_.setRate(kChorusRateHz, sampleRate);
    stutterFadeFrames_ =
        std::max(1, static_cast<int>(kStutterFadeMillis / 1000.0f * static_cast<float>(sampleRate)));

    resetState();
}

void EffectRack::setEffect(EffectId id) {
    const int32_t next = static_cast<int32_t>(id);
    if (current_.exchange(next, std::memory_order_relaxed) != next) {
        resetPending_.store(true, std::memory_order_release);
    }
}

void EffectRack::setIntensity(float intensity) {
    intensity_.setTarget(std::clamp(intensity, 0.0f, 1.0f));
}

void EffectRack::clear() {
    current_.store(static_cast<int32_t>(EffectId::None), std::memory_order_relaxed);
    intensity_.setTarget(0.0f);
    resetPending_.store(true, std::memory_order_release);
}

void EffectRack::resetState() {
    for (int ch = 0; ch < 2; ++ch) {
        for (int i = 0; i < kCombCount; ++i) {
            std::fill(comb_[ch][i].begin(), comb_[ch][i].end(), 0.0f);
            combIndex_[ch][i] = 0;
            combStore_[ch][i] = 0.0f;
        }
        for (int i = 0; i < kAllpassCount; ++i) {
            std::fill(allpass_[ch][i].begin(), allpass_[ch][i].end(), 0.0f);
            allpassIndex_[ch][i] = 0;
        }
        std::fill(delayLine_[ch].begin(), delayLine_[ch].end(), 0.0f);
        delayIndex_[ch] = 0;
        std::fill(chorusLine_[ch].begin(), chorusLine_[ch].end(), 0.0f);
        chorusIndex_[ch] = 0;
        filterState_[ch] = 0.0f;
        crushHold_[ch] = 0.0f;
    }
    std::fill(stutterBuffer_.begin(), stutterBuffer_.end(), 0.0f);
    // Start where a zero intensity would put it, so engaging the delay does not glide in from
    // somewhere the axis never asked for.
    delayFrames_ = kDelayMinSeconds * static_cast<float>(sampleRate_);
    chorusLfo_.reset();
    crushCounter_ = 0.0f;
    tremoloLfo_.reset();
    stutterWrite_ = 0;
    stutterRead_ = 0;
    stutterLength_ = 0;
    stutterCapturing_ = true;
}

void EffectRack::process(float* buffer, int32_t numFrames) {
    if (resetPending_.exchange(false, std::memory_order_acquire)) {
        resetState();
    }

    const auto id = static_cast<EffectId>(current_.load(std::memory_order_relaxed));
    if (id == EffectId::None) {
        // Keep the ramp moving so re-enabling an effect starts from the right place.
        for (int32_t f = 0; f < numFrames; ++f) intensity_.next();
        return;
    }

    switch (id) {
        case EffectId::Reverb:   processReverb(buffer, numFrames); break;
        case EffectId::Delay:    processDelay(buffer, numFrames); break;
        case EffectId::LowPass:  processFilter(buffer, numFrames, false); break;
        case EffectId::HighPass: processFilter(buffer, numFrames, true); break;
        case EffectId::BitCrush: processBitCrush(buffer, numFrames); break;
        case EffectId::Chorus:   processChorus(buffer, numFrames); break;
        case EffectId::Tremolo:  processTremolo(buffer, numFrames); break;
        case EffectId::Stutter:  processStutter(buffer, numFrames); break;
        case EffectId::None:     break;
    }
}

void EffectRack::processReverb(float* buffer, int32_t numFrames) {
    for (int32_t f = 0; f < numFrames; ++f) {
        const float wet = intensity_.next();
        // Room size grows with intensity, so the axis reads as "more space" rather than just
        // "louder tail".
        const float feedback = 0.7f + 0.25f * wet;
        const float damp = 0.2f + 0.2f * (1.0f - wet);

        for (int ch = 0; ch < 2; ++ch) {
            const float dry = buffer[f * 2 + ch];
            const float input = dry * 0.015f;
            float sum = 0.0f;

            for (int i = 0; i < kCombCount; ++i) {
                auto& line = comb_[ch][i];
                int& idx = combIndex_[ch][i];
                const float out = line[idx];
                sum += out;
                combStore_[ch][i] = out * (1.0f - damp) + combStore_[ch][i] * damp;
                line[idx] = input + combStore_[ch][i] * feedback;
                idx = (idx + 1) % static_cast<int>(line.size());
            }

            for (int i = 0; i < kAllpassCount; ++i) {
                auto& line = allpass_[ch][i];
                int& idx = allpassIndex_[ch][i];
                const float bufOut = line[idx];
                const float out = -sum + bufOut;
                line[idx] = sum + bufOut * 0.5f;
                idx = (idx + 1) % static_cast<int>(line.size());
                sum = out;
            }

            buffer[f * 2 + ch] = dry * (1.0f - wet * 0.5f) + sum * wet;
        }
    }
}

void EffectRack::processDelay(float* buffer, int32_t numFrames) {
    const int size = static_cast<int>(delayLine_[0].size());

    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        const float wanted =
            (kDelayMinSeconds + kDelaySpanSeconds * amount) * static_cast<float>(sampleRate_);
        delayFrames_ += (wanted - delayFrames_) * kDelayGlide;
        const float tap = std::clamp(delayFrames_, 1.0f, static_cast<float>(size - 2));
        const float feedback = 0.25f + 0.45f * amount;

        for (int ch = 0; ch < 2; ++ch) {
            auto& line = delayLine_[ch];
            int& idx = delayIndex_[ch];

            // Fractional read. An integer tap recomputed per frame steps a whole sample at a time
            // as the axis sweeps, and every step is a discontinuity going back into the feedback
            // path — which is the zipper you hear when the delay is swept rather than set.
            const float readPos = static_cast<float>(idx) - tap;
            const float wrapped = readPos < 0.0f ? readPos + static_cast<float>(size) : readPos;
            const int i0 = static_cast<int>(wrapped);
            const int i1 = (i0 + 1) % size;
            const float frac = wrapped - static_cast<float>(i0);
            const float delayed = line[i0] * (1.0f - frac) + line[i1] * frac;

            const float dry = buffer[f * 2 + ch];
            line[idx] = softClip(dry + delayed * feedback);
            idx = (idx + 1) % size;

            buffer[f * 2 + ch] = dry + delayed * amount;
        }
    }
}

void EffectRack::processFilter(float* buffer, int32_t numFrames, bool highPass) {
    if (numFrames <= 0) return;

    // The coefficient is evaluated at the two edges of the block and interpolated across it. The
    // intensity ramp moves at most one step per frame, so over a block the cutoff it implies is
    // very nearly a straight line — and the ramp itself still advances a frame at a time, so the
    // dry/wet mix below is exactly what it always was.
    const float coeffStart = onePoleCoeff(intensity_.current(), highPass, sampleRate_);
    const float coeffEnd = onePoleCoeff(intensity_.projected(numFrames), highPass, sampleRate_);
    const float coeffStep = (coeffEnd - coeffStart) / static_cast<float>(numFrames);
    float coeff = coeffStart;

    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        const float wet = std::min(1.0f, amount * 4.0f);

        for (int ch = 0; ch < 2; ++ch) {
            const float dry = buffer[f * 2 + ch];
            filterState_[ch] += coeff * (dry - filterState_[ch]);
            const float filtered = highPass ? (dry - filterState_[ch]) : filterState_[ch];
            buffer[f * 2 + ch] = dry + (filtered - dry) * wet;
        }
        coeff += coeffStep;
    }
}

void EffectRack::processBitCrush(float* buffer, int32_t numFrames) {
    if (numFrames <= 0) return;

    // Two degradations at once, as on the hardware: fewer bits and a lower sample rate. The bit
    // depth is a pow(); like the filter's cutoff it moves slowly enough to be evaluated at the
    // block edges and interpolated between them.
    const float levelsStart = std::max(2.0f, std::pow(2.0f, 16.0f - 14.0f * intensity_.current()));
    const float levelsEnd =
        std::max(2.0f, std::pow(2.0f, 16.0f - 14.0f * intensity_.projected(numFrames)));
    const float levelsStep = (levelsEnd - levelsStart) / static_cast<float>(numFrames);
    float levels = levelsStart;

    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        const float decimation = 1.0f + 40.0f * amount;

        crushCounter_ += 1.0f;
        const bool sampleNow = crushCounter_ >= decimation;
        if (sampleNow) crushCounter_ -= decimation;

        for (int ch = 0; ch < 2; ++ch) {
            const float dry = buffer[f * 2 + ch];
            if (sampleNow) {
                crushHold_[ch] = std::round(dry * levels) / levels;
            }
            buffer[f * 2 + ch] = dry + (crushHold_[ch] - dry) * amount;
        }
        levels += levelsStep;
    }
}

void EffectRack::processChorus(float* buffer, int32_t numFrames) {
    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        chorusLfo_.advance();

        const float baseMs = 6.0f + 8.0f * amount;
        const float depthMs = 2.0f + 6.0f * amount;

        for (int ch = 0; ch < 2; ++ch) {
            auto& line = chorusLine_[ch];
            const int size = static_cast<int>(line.size());
            int& idx = chorusIndex_[ch];

            // Quarter-cycle offset between channels widens the image — and a quarter cycle is
            // precisely the phasor's other component, since sin(p + pi/2) is cos(p). The stereo
            // spread comes free with the oscillator that replaced the per-frame sin().
            const float lfo = ch == 0 ? chorusLfo_.im : chorusLfo_.re;
            const float delayMs = baseMs + depthMs * lfo;
            const float delayFrames = delayMs / 1000.0f * static_cast<float>(sampleRate_);

            const float dry = buffer[f * 2 + ch];
            line[idx] = dry;

            // Fractional read with linear interpolation — integer taps zipper as the LFO sweeps.
            const float readPos = static_cast<float>(idx) - delayFrames;
            const float wrapped = readPos < 0 ? readPos + static_cast<float>(size) : readPos;
            const int i0 = static_cast<int>(wrapped) % size;
            const int i1 = (i0 + 1) % size;
            const float frac = wrapped - std::floor(wrapped);
            const float delayed = line[i0] * (1.0f - frac) + line[i1] * frac;

            idx = (idx + 1) % size;
            buffer[f * 2 + ch] = dry * (1.0f - amount * 0.5f) + delayed * amount;
        }
    }
    chorusLfo_.renormalise();
}

void EffectRack::processTremolo(float* buffer, int32_t numFrames) {
    if (numFrames <= 0) return;

    // The hardware calls these "fades" — slow at low intensity, choppy at high. The rate follows
    // the axis, but re-deriving it once a block is already far finer than the ear tracks, and the
    // phasor carries its angle across the change, so a rate move cannot produce a phase jump.
    const float midAmount = 0.5f * (intensity_.current() + intensity_.projected(numFrames));
    tremoloLfo_.setRate(0.5f + 11.5f * midAmount, sampleRate_);

    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        tremoloLfo_.advance();

        const float lfo = 0.5f + 0.5f * tremoloLfo_.im;
        const float gain = 1.0f - amount * (1.0f - lfo);
        buffer[f * 2] *= gain;
        buffer[f * 2 + 1] *= gain;
    }
    tremoloLfo_.renormalise();
}

void EffectRack::processStutter(float* buffer, int32_t numFrames) {
    const int capacityFrames = static_cast<int>(stutterBuffer_.size()) / 2;

    for (int32_t f = 0; f < numFrames; ++f) {
        const float amount = intensity_.next();
        if (amount <= 0.001f) {
            // Fully dry: keep capturing so engaging the effect starts from live audio.
            stutterCapturing_ = true;
            stutterWrite_ = 0;
            stutterRead_ = 0;
            continue;
        }

        // Higher intensity = shorter slice = faster repeat.
        const int sliceFrames = std::clamp(
            static_cast<int>((0.25f - 0.22f * amount) * static_cast<float>(sampleRate_)),
            64, capacityFrames);

        if (stutterCapturing_) {
            stutterBuffer_[stutterWrite_ * 2] = buffer[f * 2];
            stutterBuffer_[stutterWrite_ * 2 + 1] = buffer[f * 2 + 1];
            ++stutterWrite_;
            if (stutterWrite_ >= sliceFrames) {
                stutterLength_ = sliceFrames;
                stutterCapturing_ = false;
                stutterRead_ = 0;
            }
            continue;
        }

        if (stutterLength_ <= 0) continue;

        // The read index wraps back to the slice start, and the samples either side of that wrap
        // have nothing to do with each other — a step discontinuity, so a click on every repeat.
        // Fading both ends to zero makes the wrap continuous; at 1.5 ms the chop is untouched.
        const int fade = std::min(stutterFadeFrames_, stutterLength_ / 2);
        float window = 1.0f;
        if (fade > 0) {
            if (stutterRead_ < fade) {
                window = static_cast<float>(stutterRead_) / static_cast<float>(fade);
            } else if (stutterRead_ >= stutterLength_ - fade) {
                window = static_cast<float>(stutterLength_ - 1 - stutterRead_) /
                         static_cast<float>(fade);
            }
            window = std::clamp(window, 0.0f, 1.0f);
        }

        const float left = stutterBuffer_[stutterRead_ * 2] * window;
        const float right = stutterBuffer_[stutterRead_ * 2 + 1] * window;
        stutterRead_ = (stutterRead_ + 1) % stutterLength_;

        buffer[f * 2] += (left - buffer[f * 2]) * amount;
        buffer[f * 2 + 1] += (right - buffer[f * 2 + 1]) * amount;
    }
}

} // namespace estem
