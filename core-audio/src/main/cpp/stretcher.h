#pragma once

#include <cstdint>
#include <vector>

#include "stems.h"

namespace estem {

/**
 * WSOLA time-stretch across four stems driven by **one** analysis path.
 *
 * Playing a track faster by moving the cursor faster also raises its pitch, which is a turntable,
 * not a tempo control. This cuts the source into overlapping grains and overlap-adds them at a
 * different spacing than it took them: emit them closer together than they were and the track gets
 * shorter without any sample being read at anything other than its own rate, so the pitch does not
 * move.
 *
 * Grains have to be laid down where the waveform continues rather than on a fixed grid — butting
 * two grains together at an arbitrary phase is what makes naive overlap-add sound like a flanger.
 * So each grain is searched for: the offset within a window either side of the nominal position
 * whose material best continues the tail of the grain already emitted.
 *
 * **The search runs once, on the sum of the four stems, and every stem then uses that same
 * offset.** That is the whole reason this can exist at all. Searching per stem would give four
 * different offsets, which is four stems being shifted in time by different amounts — the exact
 * drift the single shared cursor exists to prevent, reintroduced a grain at a time.
 *
 * Nothing here allocates, locks, or blocks once [prepare] has run.
 */
class TimeStretcher {
public:
    /** Worker thread only — allocates. */
    void prepare(int32_t sampleRate);

    /**
     * Drops every grain in flight and starts clean at `sourcePos`.
     *
     * Audio thread safe. Required after a seek or a loop wrap: the overlap buffer still holds
     * windowed audio from where the cursor used to be, and left alone it would be mixed into the
     * new position.
     *
     * Seamless by construction — it lays down the grain that *would* have been emitted one hop
     * ago, so the first output frame already sits under two overlapping windows. A lone opening
     * grain would instead taper the output up from silence over half a window, which shows up as a
     * dip every time the stretcher is engaged, seeks, or comes round a loop.
     */
    void reset(const StemSource& source, double sourcePos);

    /**
     * Audio thread. Emits one output frame per stem and advances the overlap buffer by one.
     *
     * @param sourcePos where the deck's cursor has reached, in source frames. The stretcher does
     * not advance this itself — the deck moves it at the playback rate exactly as it does when
     * stretching is off, so the loop region, seeking and end-of-track behaviour keep living in one
     * place, and the analysis hop falls out of the cursor's own speed.
     */
    void next(const StemSource& source, double sourcePos, float* left, float* right);

private:
    void addGrain(const StemSource& source, double sourcePos);
    /** Best grain offset in frames, searched on the summed stems. */
    int32_t findOffset(const StemSource& source, int64_t nominal);
    /** Boxcar-decimated mono sum of every stem, for the correlation. */
    void fillDecimated(const StemSource& source, int64_t from, int32_t count, float* into);

    int32_t window_ = 0;         // grain length in frames
    int32_t synthesisHop_ = 0;   // frames between emitted grains — always window_ / 2
    int32_t correlation_ = 0;    // frames of tail matched against each candidate
    int32_t search_ = 0;         // how far either side of nominal a grain may be taken from

    std::vector<float> hann_;
    /**
     * Circular overlap-add accumulator per stem, stereo interleaved, `window_` frames long.
     *
     * Each slot is zeroed as it is read, so the buffer is always exactly the sum of the grains
     * still in flight over it and a fresh grain can be added straight on top.
     */
    std::vector<float> overlap_[kStemCount];
    int32_t overlapIndex_ = 0;
    int32_t untilNextGrain_ = 0;

    /** Where the previous grain was taken from. Its tail is what the next one has to continue. */
    int64_t previousGrain_ = 0;
    bool primed_ = false;

    // Decimated correlation scratch, sized once in prepare().
    std::vector<float> tail_;
    std::vector<float> candidates_;
};

} // namespace estem
