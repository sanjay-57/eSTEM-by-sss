#pragma once

#include <atomic>
#include <cmath>

namespace estem {

/**
 * A gain the UI thread can write at any time and the audio thread can read every frame without
 * a lock. The target is a relaxed atomic; the current value walks toward it one frame at a time.
 *
 * Jumping straight to a new gain produces an audible click on every touch move, and the puck
 * sends a lot of touch moves, so every gain in the signal path goes through one of these.
 */
class Ramp {
public:
    void setSampleRate(int32_t sampleRate, float rampMillis = kDefaultRampMs) {
        const float frames = (rampMillis / 1000.0f) * static_cast<float>(sampleRate);
        step_ = frames > 1.0f ? 1.0f / frames : 1.0f;
    }

    /** Called from the UI thread. */
    void setTarget(float target) { target_.store(target, std::memory_order_relaxed); }

    /** Called from the UI thread to jump without a ramp, e.g. when loading a new track. */
    void reset(float value) {
        target_.store(value, std::memory_order_relaxed);
        current_ = value;
    }

    float target() const { return target_.load(std::memory_order_relaxed); }

    /** Audio thread only. Advances one frame and returns the gain for it. */
    inline float next() {
        const float target = target_.load(std::memory_order_relaxed);
        const float delta = target - current_;
        if (delta > step_) {
            current_ += step_;
        } else if (delta < -step_) {
            current_ -= step_;
        } else {
            current_ = target;
        }
        return current_;
    }

    inline float current() const { return current_; }

    /**
     * Where [next] will have reached after `frames` more calls, without advancing anything.
     *
     * Lets the audio thread hoist an expensive derived value — a filter coefficient, an LFO rate —
     * out to the two edges of a block and interpolate between them, instead of recomputing it at
     * audio rate. The ramp itself still advances one frame at a time, so nothing downstream of it
     * sees a coarser signal.
     */
    inline float projected(int32_t frames) const {
        const float target = target_.load(std::memory_order_relaxed);
        const float delta = target - current_;
        const float reach = step_ * static_cast<float>(frames);
        if (delta > reach) return current_ + reach;
        if (delta < -reach) return current_ - reach;
        return target;
    }

    /** True once the ramp has settled — lets the engine skip work on silent stems. */
    inline bool settledAt(float value) const {
        return std::fabs(current_ - value) < 1e-6f &&
               std::fabs(target_.load(std::memory_order_relaxed) - value) < 1e-6f;
    }

private:
    static constexpr float kDefaultRampMs = 5.0f;

    std::atomic<float> target_{0.0f};
    float current_{0.0f};
    // Fraction of full scale to move per frame. Set by setSampleRate.
    float step_{1.0f};
};

} // namespace estem
