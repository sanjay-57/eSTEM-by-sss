package com.sss.estem.data.model

/**
 * The four stems, in the order they appear left-to-right on the hardware's face. The ordinal is
 * used as the channel index throughout the audio engine and as the ONNX output index, so do not
 * reorder these.
 */
enum class Stem(val displayName: String, val fileName: String) {
    VOCALS("Vocals", "vocals"),
    DRUMS("Drums", "drums"),
    BASS("Bass", "bass"),
    MELODY("Melody", "other");

    companion object {
        const val COUNT = 4
        val ORDER: List<Stem> = entries
    }
}

enum class SeparationState {
    /** Imported, waiting for a separation worker slot. */
    PENDING,

    /** A worker is currently running htdemucs over this track. */
    RUNNING,

    /** Four stem files exist on disk and the track is playable. */
    READY,

    /** Separation failed; see [com.sss.estem.data.db.Track.separationError]. */
    FAILED,
}
