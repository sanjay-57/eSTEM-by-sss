#include "recorder.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "estem.rec"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace estem {

namespace {

constexpr int kChannels = 2;
constexpr int kBitsPerSample = 16;
constexpr int kHeaderBytes = 44;

void writeU32(std::FILE* f, uint32_t v) { std::fwrite(&v, 4, 1, f); }
void writeU16(std::FILE* f, uint16_t v) { std::fwrite(&v, 2, 1, f); }

/** Writes a WAV header with zeroed sizes; [Recorder::finaliseHeader] patches them on stop. */
void writePlaceholderHeader(std::FILE* f, int32_t sampleRate) {
    std::fwrite("RIFF", 1, 4, f);
    writeU32(f, 0);
    std::fwrite("WAVE", 1, 4, f);
    std::fwrite("fmt ", 1, 4, f);
    writeU32(f, 16);
    writeU16(f, 1); // PCM
    writeU16(f, kChannels);
    writeU32(f, static_cast<uint32_t>(sampleRate));
    writeU32(f, static_cast<uint32_t>(sampleRate * kChannels * kBitsPerSample / 8));
    writeU16(f, kChannels * kBitsPerSample / 8);
    writeU16(f, kBitsPerSample);
    std::fwrite("data", 1, 4, f);
    writeU32(f, 0);
}

inline int16_t toPcm16(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 1.0f);
    return static_cast<int16_t>(std::lrintf(clamped * 32767.0f));
}

} // namespace

Recorder::~Recorder() {
    stop();
}

bool Recorder::start(const std::string& path, int32_t sampleRate, int64_t maxFrames) {
    if (recording_.load(std::memory_order_acquire)) return false;

    // Reap the previous run before touching writer_. A recording that reached the frame cap ended
    // itself from inside writerLoop, so its thread has exited but is still *joinable* — and
    // assigning over a joinable std::thread calls std::terminate, which aborts the process. That
    // is why the second 60-second recording killed the app rather than the first.
    finish();

    file_ = std::fopen(path.c_str(), "wb");
    if (file_ == nullptr) {
        LOGW("could not open %s", path.c_str());
        return false;
    }

    path_ = path;
    sampleRate_ = sampleRate;
    maxFrames_ = maxFrames;
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
    framesWritten_.store(0, std::memory_order_relaxed);
    overflowFrames_.store(0, std::memory_order_relaxed);

    writePlaceholderHeader(file_, sampleRate);

    recording_.store(true, std::memory_order_release);
    writer_ = std::thread(&Recorder::writerLoop, this);
    return true;
}

void Recorder::stop() {
    // Unconditionally, rather than gated on the flag: a recording that hit its cap already cleared
    // the flag from the writer thread, and gating here meant its file was never closed and its WAV
    // header never patched — so every full-length recording on disk claimed a data size of zero.
    recording_.store(false, std::memory_order_release);
    finish();
}

/**
 * Joins the writer and closes out the file. Safe to call when there is nothing to close, which is
 * what lets both [stop] and a fresh [start] go through it.
 */
void Recorder::finish() {
    if (writer_.joinable()) writer_.join();
    if (file_ == nullptr) return;

    const int64_t dropped = overflowFrames_.load(std::memory_order_relaxed);
    if (dropped > 0) LOGW("dropped %lld frames — writer fell behind", static_cast<long long>(dropped));

    finaliseHeader();
    std::fclose(file_);
    file_ = nullptr;
    LOGI("recorded %lld frames to %s", static_cast<long long>(framesWritten_.load()), path_.c_str());
}

void Recorder::capture(const float* buffer, int32_t numFrames) {
    if (!recording_.load(std::memory_order_acquire)) return;

    const size_t write = writeIndex_.load(std::memory_order_relaxed);
    const size_t read = readIndex_.load(std::memory_order_acquire);
    const size_t used = write - read;
    const size_t free = kRingFloats - used;
    const size_t wanted = static_cast<size_t>(numFrames) * 2;

    if (wanted > free) {
        overflowFrames_.fetch_add(numFrames, std::memory_order_relaxed);
        return;
    }

    for (size_t i = 0; i < wanted; ++i) {
        ring_[(write + i) % kRingFloats] = buffer[i];
    }
    writeIndex_.store(write + wanted, std::memory_order_release);
}

void Recorder::writerLoop() {
    std::vector<int16_t> scratch(4096);

    while (true) {
        const bool stillRecording = recording_.load(std::memory_order_acquire);
        const size_t write = writeIndex_.load(std::memory_order_acquire);
        size_t read = readIndex_.load(std::memory_order_relaxed);

        if (read == write) {
            if (!stillRecording) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        size_t available = write - read;
        while (available > 0) {
            const size_t chunk = std::min(available, scratch.size());
            for (size_t i = 0; i < chunk; ++i) {
                scratch[i] = toPcm16(ring_[(read + i) % kRingFloats]);
            }
            std::fwrite(scratch.data(), sizeof(int16_t), chunk, file_);
            read += chunk;
            available -= chunk;

            const int64_t frames = framesWritten_.fetch_add(
                static_cast<int64_t>(chunk / 2), std::memory_order_relaxed) +
                static_cast<int64_t>(chunk / 2);
            if (maxFrames_ > 0 && frames >= maxFrames_) {
                readIndex_.store(read, std::memory_order_release);
                // Hit the cap (the hardware's 60 s); tell the audio thread to stop feeding us.
                recording_.store(false, std::memory_order_release);
                return;
            }
        }
        readIndex_.store(read, std::memory_order_release);
    }
}

void Recorder::finaliseHeader() {
    if (file_ == nullptr) return;
    std::fflush(file_);

    const int64_t frames = framesWritten_.load(std::memory_order_relaxed);
    const auto dataBytes = static_cast<uint32_t>(frames * kChannels * kBitsPerSample / 8);

    std::fseek(file_, 4, SEEK_SET);
    writeU32(file_, dataBytes + kHeaderBytes - 8);
    std::fseek(file_, 40, SEEK_SET);
    writeU32(file_, dataBytes);
    std::fflush(file_);
}

} // namespace estem
