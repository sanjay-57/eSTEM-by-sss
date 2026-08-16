#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace estem {

/**
 * Taps the engine's output into a WAV file.
 *
 * The audio thread only ever pushes into a single-producer/single-consumer ring; a writer thread
 * drains it to disk. Writing a file from the render callback would stall it on I/O and produce
 * exactly the dropouts a recording is meant to capture cleanly.
 *
 * The hardware records 60 seconds by default, so [start] takes a frame cap and stops itself.
 */
class Recorder {
public:
    ~Recorder();

    /** Worker thread only. */
    bool start(const std::string& path, int32_t sampleRate, int64_t maxFrames);

    /**
     * Worker thread only. Flushes the ring and patches the WAV header.
     *
     * Idempotent, and required even when [isRecording] already reads false: hitting the frame cap
     * clears that flag from inside the writer thread, but the thread still has to be joined and
     * the file still has to be closed.
     */
    void stop();

    bool isRecording() const { return recording_.load(std::memory_order_acquire); }
    int64_t framesWritten() const { return framesWritten_.load(std::memory_order_relaxed); }

    /**
     * Audio thread only. Stereo interleaved. Drops frames rather than blocking if the writer
     * thread has fallen behind — a glitch in the recording beats a glitch in playback.
     */
    void capture(const float* buffer, int32_t numFrames);

private:
    void writerLoop();
    void finish();
    void finaliseHeader();

    static constexpr size_t kRingFrames = 1 << 16; // ~1.4 s of stereo at 48 kHz
    static constexpr size_t kRingFloats = kRingFrames * 2;

    std::vector<float> ring_{std::vector<float>(kRingFloats, 0.0f)};
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};

    std::atomic<bool> recording_{false};
    std::atomic<int64_t> framesWritten_{0};
    std::atomic<int64_t> overflowFrames_{0};

    int64_t maxFrames_ = 0;
    int32_t sampleRate_ = 48000;
    std::string path_;
    std::FILE* file_ = nullptr;
    std::thread writer_;
};

} // namespace estem
