#pragma once

#include <oboe/Oboe.h>

#include <memory>
#include <mutex>
#include <string>

#include "effects.h"
#include "ramp.h"
#include "recorder.h"
#include "stem_deck.h"

namespace estem {

/** Two decks: A and B. */
constexpr int kDeckCount = 2;

/**
 * Owns the single Oboe output stream and everything downstream of the decks.
 *
 * Signal path, per callback:
 *   deck A.render + deck B.render (both additive) -> effects rack -> master -> recorder -> stream
 *
 * Everything the user hears comes out of this one stream. Four MediaPlayers, or one stream per
 * stem, cannot stay sample-aligned; one stream summing mmapped sources cannot fall out of
 * alignment because there is nothing to align.
 *
 * The second deck is what makes two-song mixing possible, and it costs almost nothing structurally
 * because [StemDeck] never assumed it was the only one — it renders additively into a buffer it
 * does not own and carries its own share of the mix as an output gain.
 */
class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine();
    ~Engine() override;

    bool start();
    void stop();
    bool isRunning() const;

    /**
     * Maps a new set of stems onto one deck. Reopens the stream if the track's sample rate differs
     * from the one currently open — decks always render 1:1 and let Oboe convert to the device
     * rate, which keeps our render path free of resampling.
     *
     * With two decks loaded at different rates, the stream follows whichever was loaded last and
     * the other is converted by its own read rate. Mixing 44.1 and 48 kHz material is the one case
     * where a deck is not reading at exactly 1:1, and it is inaudible next to the tempo change
     * that beat-matching them applies anyway.
     */
    bool loadTrack(int deck, const std::string paths[kStemCount], int32_t sampleRate,
                   int32_t channelCount);
    void unloadTrack(int deck);

    /** Deck 0 is A, deck 1 is B. Out-of-range indices are clamped rather than crashing. */
    StemDeck& deck(int index);
    EffectRack& effects() { return effects_; }
    Recorder& recorder() { return recorder_; }

    /**
     * 0 is all of deck A, 1 is all of deck B.
     *
     * Equal-power rather than linear: two different songs are uncorrelated, so their powers add
     * where their amplitudes do not, and a linear fade through the middle audibly dips. Cosine and
     * sine keep the sum of powers constant all the way across.
     */
    void setCrossfade(float position);
    float crossfade() const { return crossfade_; }

    void setMasterVolume(float volume) { master_.setTarget(std::clamp(volume, 0.0f, 1.0f)); }
    float masterVolume() const { return master_.target(); }

    int32_t streamSampleRate() const { return streamSampleRate_; }
    /** Underrun count since the stream opened — the cheapest glitch diagnostic we have. */
    int32_t xRunCount() const;
    /** Current buffer size in frames. Grows on its own if the stream cannot hold its deadline. */
    int32_t bufferFrames() const;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream(int32_t sampleRate);
    void closeStream();

    /** Guards open/close/load against each other. Never taken on the audio thread. */
    mutable std::mutex lifecycleMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    /**
     * Grows the buffer when the stream cannot hold its deadline.
     *
     * Opening at two bursts is 4.35 ms at 44.1 kHz, and measured on a real device that missed a
     * deadline roughly every two seconds *at 1x with nothing but the deck running* — before the
     * stretcher, which only made a bad number slightly worse. A fixed buffer has to be sized for
     * the worst moment the phone will ever have, which means giving up latency permanently to pay
     * for an occasional one. This starts where it always did and gives ground a burst at a time,
     * only if the device actually asks for it.
     *
     * Owned rather than a plain call because it must be driven from the data callback, which is
     * the only place that knows a callback happened.
     */
    std::unique_ptr<oboe::LatencyTuner> tuner_;

    StemDeck decks_[kDeckCount];
    EffectRack effects_;
    Recorder recorder_;
    Ramp master_;
    /** Crossfader position; the derived per-deck gains live on the decks and ramp there. */
    float crossfade_ = 0.0f;

    int32_t streamSampleRate_ = 48000;
    bool wantRunning_ = false;

    /**
     * Feed-forward limiter state, audio thread only.
     *
     * Four stems summed at unity reconstruct the full mix, which on a mastered track already sits
     * near 0 dBFS — and delay feedback or reverb on top of that will overshoot. Without this the
     * sum hard-clips against the float stream and the result is gross distortion, not a gentle
     * squash.
     */
    float limiterGain_ = 1.0f;
};

} // namespace estem
