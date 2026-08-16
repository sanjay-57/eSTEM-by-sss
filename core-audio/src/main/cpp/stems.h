#pragma once

#include <cstdint>

namespace estem {

/**
 * The four stems, in `com.sss.estem.data.model.Stem` order.
 *
 * Its own header because both the deck and the stretcher are indexed by it, and the stretcher
 * cannot include the deck — the deck owns one.
 */
constexpr int kStemCount = 4;

/**
 * The four mapped stems, handed to the stretcher for the duration of one block.
 *
 * A view, not an owner: the mappings belong to the deck, which guarantees they outlive the call by
 * keeping the audio thread out of the render path entirely while they are being swapped.
 */
struct StemSource {
    const int16_t* samples[kStemCount] = {};
    int64_t frames = 0;
    int32_t channels = 2;
};

} // namespace estem
