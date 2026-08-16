package com.sss.estem.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sss.estem.data.model.SeparationState

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["sourceUri"], unique = true)],
)
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Persisted SAF document URI of the user's original file. */
    val sourceUri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val mimeType: String? = null,
    val separationState: SeparationState = SeparationState.PENDING,
    /** 0f..1f, written by the separation worker so the library list can show a bar. */
    val separationProgress: Float = 0f,
    val separationError: String? = null,
    val addedAt: Long = 0,
)

/**
 * The four separated files for a track. One row per track — the unique index enforces that
 * re-separating replaces rather than accumulates.
 */
@Entity(
    tableName = "stem_sets",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId"], unique = true)],
)
data class StemSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val vocalsPath: String,
    val drumsPath: String,
    val bassPath: String,
    val melodyPath: String,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
    val createdAt: Long,
    /**
     * Detected tempo, or 0 when no steady pulse was found.
     *
     * Stored beside the stems rather than on the track because it is measured *from* the drums
     * stem — it only exists once separation has run, and re-separating is what would change it.
     */
    val bpm: Float = 0f,
    /** Frame of the first detected beat; every other beat is this plus a multiple of the period. */
    val firstBeatFrame: Long = 0,
)

/**
 * A captured mix. The hardware keeps exactly four, oldest rolling off — [slot] is 0..3 and the
 * repository enforces the rollover.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slot: Int,
    val path: String,
    val sourceTrackId: Long? = null,
    val title: String,
    val durationMs: Long,
    val createdAt: Long,
)
