#include "stretcher.h"

#include <algorithm>
#include <cmath>

namespace estem {

namespace {

constexpr float kInt16Scale = 1.0f / 32768.0f;

/**
 * Grain length at 48 kHz, scaled to the real rate in prepare(). About 43 ms.
 *
 * The one number that decides how this sounds. Shorter and sustained material gets a warble as the
 * grain rate lands in the audible range; longer and transients get repeated or dropped audibly,
 * because a drum hit inside a grain is moved bodily by however far the grain moved.
 */
constexpr int32_t kWindowAt48k = 2048;

/**
 * The correlation runs on a mono sum decimated by this much.
 *
 * A boxcar average rather than plain subsampling — dropping three samples in four aliases the
 * signal being matched, and the peak found in an aliased correlation is not the peak. Four is
 * enough to make the search cost disappear and far finer than grain alignment needs.
 */
constexpr int32_t kDecimation = 4;

} // namespace

void TimeStretcher::prepare(int32_t sampleRate) {
    // Even, because the synthesis hop is exactly half of it: Hann windows overlapping by half sum
    // to a constant, so the overlap-add needs no normalisation pass.
    window_ = std::max(256, static_cast<int32_t>(
                                static_cast<int64_t>(kWindowAt48k) * sampleRate / 48000));
    window_ &= ~1;
    synthesisHop_ = window_ / 2;
    correlation_ = window_ / 4;
    search_ = window_ / 4;

    hann_.resize(static_cast<size_t>(window_));
    for (int32_t i = 0; i < window_; ++i) {
        hann_[i] = 0.5f - 0.5f * std::cos(6.28318530718f * static_cast<float>(i) /
                                          static_cast<float>(window_));
    }

    for (auto& stem : overlap_) {
        stem.assign(static_cast<size_t>(window_) * 2, 0.0f);
    }

    tail_.assign(static_cast<size_t>(correlation_ / kDecimation) + 2, 0.0f);
    candidates_.assign(static_cast<size_t>((2 * search_ + correlation_) / kDecimation) + 2, 0.0f);

    for (auto& stem : overlap_) {
        std::fill(stem.begin(), stem.end(), 0.0f);
    }
    overlapIndex_ = 0;
    untilNextGrain_ = 0;
    previousGrain_ = 0;
    primed_ = false;
}

void TimeStretcher::reset(const StemSource& source, double sourcePos) {
    for (auto& stem : overlap_) {
        std::fill(stem.begin(), stem.end(), 0.0f);
    }
    overlapIndex_ = 0;
    untilNextGrain_ = 0;

    if (window_ <= 0 || source.frames < window_) {
        previousGrain_ = 0;
        primed_ = false;
        return;
    }

    const int64_t at =
        std::clamp<int64_t>(std::llround(sourcePos), 0, source.frames - 1);
    // Where the grain one hop back would have started, which is what the first search has to
    // continue from. Its tail — the frames from `at` onward — is what gets pre-loaded below.
    previousGrain_ = at - synthesisHop_;
    primed_ = true;

    // The second half of that notional previous grain, covering output frames [0, hop). Paired
    // with the first half of the grain the next call lays down at `at`, the two Hann halves sum to
    // one — so the stretcher starts at full level, in phase, reading forward from `at` only.
    const int32_t channels = source.channels;
    const int64_t available = source.frames - at;
    const int32_t span = static_cast<int32_t>(
        std::min<int64_t>(synthesisHop_, std::max<int64_t>(0, available)));

    for (int s = 0; s < kStemCount; ++s) {
        const int16_t* samples = source.samples[s];
        if (samples == nullptr) continue;
        float* accumulator = overlap_[s].data();

        for (int32_t t = 0; t < span; ++t) {
            const int64_t frame = (at + t) * channels;
            const float sampleL = static_cast<float>(samples[frame]) * kInt16Scale;
            const float sampleR = channels == 1
                ? sampleL
                : static_cast<float>(samples[frame + 1]) * kInt16Scale;
            const float shaped = hann_[synthesisHop_ + t];
            accumulator[t * 2] += shaped * sampleL;
            accumulator[t * 2 + 1] += shaped * sampleR;
        }
    }
}

void TimeStretcher::next(const StemSource& source, double sourcePos, float* left, float* right) {
    if (window_ <= 0) return;

    if (untilNextGrain_ <= 0) {
        addGrain(source, sourcePos);
        untilNextGrain_ = synthesisHop_;
    }

    const int32_t slot = overlapIndex_ * 2;
    for (int s = 0; s < kStemCount; ++s) {
        float* accumulator = overlap_[s].data();
        left[s] = accumulator[slot];
        right[s] = accumulator[slot + 1];
        // Zeroed on the way out, so what remains is exactly the grains still in flight and the
        // next one can be added straight on top without a separate clearing pass.
        accumulator[slot] = 0.0f;
        accumulator[slot + 1] = 0.0f;
    }

    overlapIndex_ = overlapIndex_ + 1 < window_ ? overlapIndex_ + 1 : 0;
    --untilNextGrain_;
}

void TimeStretcher::addGrain(const StemSource& source, double sourcePos) {
    const int64_t lastStart = source.frames - window_;
    if (lastStart <= 0) return;

    const int64_t nominal = static_cast<int64_t>(std::llround(sourcePos));
    int64_t start = primed_ ? nominal + findOffset(source, nominal) : nominal;
    primed_ = true;
    start = std::clamp<int64_t>(start, 0, lastStart);
    previousGrain_ = start;

    const int32_t channels = source.channels;

    // The grain is exactly as long as the buffer, so it wraps precisely once. Working out where
    // that happens up front keeps a modulo out of a loop that runs window_ times per stem.
    const int32_t firstRun = window_ - overlapIndex_;

    for (int s = 0; s < kStemCount; ++s) {
        const int16_t* samples = source.samples[s];
        if (samples == nullptr) continue;
        float* accumulator = overlap_[s].data();

        for (int32_t i = 0; i < window_; ++i) {
            const int64_t frame = (start + i) * channels;
            const float sampleL = static_cast<float>(samples[frame]) * kInt16Scale;
            const float sampleR = channels == 1
                ? sampleL
                : static_cast<float>(samples[frame + 1]) * kInt16Scale;

            const int32_t at = (i < firstRun ? overlapIndex_ + i : i - firstRun) * 2;
            const float shaped = hann_[i];
            accumulator[at] += shaped * sampleL;
            accumulator[at + 1] += shaped * sampleR;
        }
    }
}

int32_t TimeStretcher::findOffset(const StemSource& source, int64_t nominal) {
    const int32_t tailCount = correlation_ / kDecimation;
    const int32_t candidateCount = (2 * search_ + correlation_) / kDecimation;
    const int32_t lagCount = candidateCount - tailCount;
    if (tailCount <= 0 || lagCount <= 0) return 0;

    // What the previous grain would have run into had it simply carried on. A candidate that
    // continues this is a candidate that can be crossfaded onto it without a phase jump.
    fillDecimated(source, previousGrain_ + synthesisHop_, tailCount, tail_.data());
    fillDecimated(source, nominal - search_, candidateCount, candidates_.data());

    int32_t bestLag = 0;
    float bestScore = -1e30f;

    for (int32_t lag = 0; lag <= lagCount; ++lag) {
        const float* candidate = candidates_.data() + lag;
        float correlation = 0.0f;
        float energy = 0.0f;
        for (int32_t i = 0; i < tailCount; ++i) {
            const float c = candidate[i];
            correlation += tail_[i] * c;
            energy += c * c;
        }
        // Normalised, otherwise the search just walks toward whichever candidate is loudest and a
        // quiet passage next to a loud one always aligns onto the loud one.
        const float score = correlation / std::sqrt(energy + 1e-9f);
        if (score > bestScore) {
            bestScore = score;
            bestLag = lag;
        }
    }

    return bestLag * kDecimation - search_;
}

void TimeStretcher::fillDecimated(const StemSource& source, int64_t from, int32_t count,
                                  float* into) {
    const int64_t span = static_cast<int64_t>(count) * kDecimation;
    // Clamped as a whole range rather than per sample, so the inner loop stays branch-free. The
    // search is relative, so sliding it a few frames to stay inside the track costs nothing.
    const int64_t start = std::clamp<int64_t>(from, 0, std::max<int64_t>(0, source.frames - span));
    const int32_t channels = source.channels;

    if (source.frames < span) {
        std::fill(into, into + count, 0.0f);
        return;
    }

    constexpr float kNormalise = 1.0f / static_cast<float>(kDecimation * kStemCount);

    for (int32_t k = 0; k < count; ++k) {
        float sum = 0.0f;
        for (int32_t d = 0; d < kDecimation; ++d) {
            const int64_t frame = (start + static_cast<int64_t>(k) * kDecimation + d) * channels;
            for (int s = 0; s < kStemCount; ++s) {
                const int16_t* samples = source.samples[s];
                if (samples == nullptr) continue;
                const float sampleL = static_cast<float>(samples[frame]);
                const float sampleR =
                    channels == 1 ? sampleL : static_cast<float>(samples[frame + 1]);
                sum += 0.5f * (sampleL + sampleR);
            }
        }
        into[k] = sum * kNormalise * kInt16Scale;
    }
}

} // namespace estem
