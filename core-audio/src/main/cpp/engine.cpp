#include "engine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "estem.engine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace estem {

namespace {
constexpr int32_t kChannelCount = 2;
constexpr int32_t kDefaultSampleRate = 48000;
/** Two bursts is the usual sweet spot: low latency without living on the underrun edge. */
constexpr int32_t kBurstsPerBuffer = 2;

/** A hair under full scale, so nothing ever reaches the stream's clip point. */
constexpr float kLimiterCeiling = 0.97f;
constexpr float kLimiterAttack = 0.40f;
constexpr float kLimiterRelease = 0.0008f;

constexpr float kPiOverTwo = 1.57079632679f;
} // namespace

Engine::Engine() {
    master_.setSampleRate(kDefaultSampleRate);
    master_.reset(1.0f);
    effects_.prepare(kDefaultSampleRate);
    for (auto& deck : decks_) deck.setEngineSampleRate(kDefaultSampleRate);

    // Fully on A. Primed rather than set, so the first track does not fade in from silence.
    decks_[0].primeOutputGain(1.0f);
    decks_[1].primeOutputGain(0.0f);
}

StemDeck& Engine::deck(int index) {
    return decks_[std::clamp(index, 0, kDeckCount - 1)];
}

void Engine::setCrossfade(float position) {
    crossfade_ = std::clamp(position, 0.0f, 1.0f);
    const float angle = crossfade_ * kPiOverTwo;
    decks_[0].setOutputGain(std::cos(angle));
    decks_[1].setOutputGain(std::sin(angle));
}

Engine::~Engine() {
    stop();
}

bool Engine::start() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    wantRunning_ = true;
    if (stream_ != nullptr) return true;
    return openStream(streamSampleRate_);
}

void Engine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    wantRunning_ = false;
    for (auto& deck : decks_) deck.setPlaying(false);
    recorder_.stop();
    closeStream();
}

bool Engine::isRunning() const {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    return stream_ != nullptr && stream_->getState() == oboe::StreamState::Started;
}

bool Engine::openStream(int32_t sampleRate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(kChannelCount)
        ->setSampleRate(sampleRate)
        // We render at the track's own rate; Oboe converts to the device rate for us, which
        // keeps resampling out of our render path entirely.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * kBurstsPerBuffer);

    // Created before the stream starts, so the callback can never see a half-assigned tuner.
    tuner_ = std::make_unique<oboe::LatencyTuner>(*stream);

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(result));
        tuner_.reset();
        stream->close();
        return false;
    }

    stream_ = stream;
    streamSampleRate_ = sampleRate;
    limiterGain_ = 1.0f;
    master_.setSampleRate(sampleRate);
    effects_.prepare(sampleRate);
    for (auto& deck : decks_) deck.setEngineSampleRate(sampleRate);

    LOGI("stream open: %d Hz, burst %d, buffer %d", sampleRate, stream->getFramesPerBurst(),
         stream->getBufferSizeInFrames());
    return true;
}

void Engine::closeStream() {
    if (stream_ == nullptr) return;
    stream_->requestStop();
    // close() is the point after which no further callback can be running, so the tuner the
    // callback uses cannot be released before it.
    stream_->close();
    tuner_.reset();
    stream_.reset();
}

bool Engine::loadTrack(int index,
                       const std::string paths[kStemCount],
                       int32_t sampleRate,
                       int32_t channelCount) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    const int slot = std::clamp(index, 0, kDeckCount - 1);

    // Reopening for a rate change is cheap and happens only when the library mixes 44.1k and
    // 48k material. Doing it here keeps render() free of a resampler.
    if (stream_ != nullptr && sampleRate != streamSampleRate_) {
        closeStream();
        if (!openStream(sampleRate)) {
            LOGW("could not reopen at %d Hz, falling back", sampleRate);
            openStream(kDefaultSampleRate);
        }
    } else if (stream_ == nullptr && wantRunning_) {
        openStream(sampleRate);
    }

    if (!decks_[slot].load(paths, sampleRate, channelCount)) return false;
    // Only when the deck being loaded is the one you can hear. Cueing a track to the silent deck
    // must not strip the effect that is currently running on the one playing.
    if (decks_[slot].outputGain() > 0.0f) effects_.clear();
    return true;
}

void Engine::unloadTrack(int index) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    decks_[std::clamp(index, 0, kDeckCount - 1)].unload();
}

int32_t Engine::xRunCount() const {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (stream_ == nullptr) return 0;
    auto result = stream_->getXRunCount();
    return result ? result.value() : 0;
}

int32_t Engine::bufferFrames() const {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    return stream_ == nullptr ? 0 : stream_->getBufferSizeInFrames();
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream* /*stream*/,
                                              void* audioData,
                                              int32_t numFrames) {
    // Before the work, not after: it reads the underrun count the *previous* callbacks caused, and
    // a buffer that grows one burst late is a buffer that glitched first.
    if (tuner_ != nullptr) tuner_->tune();

    auto* out = static_cast<float*>(audioData);
    // The deck sums into this buffer, so it has to start at silence.
    std::memset(out, 0, static_cast<size_t>(numFrames) * kChannelCount * sizeof(float));

    // Both decks sum into the same buffer, which is the entire mechanism behind two-song mixing:
    // there is one stream and one cursor per song, and no clock anywhere for them to drift against.
    for (auto& deck : decks_) deck.render(out, numFrames);
    effects_.process(out, numFrames);

    for (int32_t f = 0; f < numFrames; ++f) {
        const float gain = master_.next();
        float left = out[f * 2] * gain;
        float right = out[f * 2 + 1] * gain;

        // Limiter: pull the gain down fast when a peak would exceed the ceiling, let it back up
        // slowly. Fast attack stops the overshoot ever reaching the stream; slow release keeps it
        // from pumping audibly between peaks.
        const float peak = std::fmax(std::fabs(left), std::fabs(right));
        const float target = peak > kLimiterCeiling ? kLimiterCeiling / peak : 1.0f;
        limiterGain_ += (target - limiterGain_) *
                        (target < limiterGain_ ? kLimiterAttack : kLimiterRelease);

        left *= limiterGain_;
        right *= limiterGain_;

        // Belt and braces: the limiter is smoothed, so a single sample can still slip past it.
        out[f * 2] = std::fmin(1.0f, std::fmax(-1.0f, left));
        out[f * 2 + 1] = std::fmin(1.0f, std::fmax(-1.0f, right));
    }

    recorder_.capture(out, numFrames);

    return oboe::DataCallbackResult::Continue;
}

void Engine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Fires on headphone unplug, Bluetooth switch, or the device being taken away. Oboe has
    // already closed the stream, so all we can do is open a fresh one.
    LOGW("stream error, reopening: %s", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stream_.reset();
    if (wantRunning_) {
        openStream(streamSampleRate_);
    }
}

} // namespace estem
