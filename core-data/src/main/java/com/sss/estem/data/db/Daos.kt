package com.sss.estem.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.sss.estem.data.model.SeparationState
import kotlinx.coroutines.flow.Flow

data class TrackWithStems(
    @Embedded val track: Track,
    @Relation(parentColumn = "id", entityColumn = "trackId")
    val stems: StemSet?,
)

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE sourceUri = :uri")
    suspend fun bySourceUri(uri: String): Track?

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<TrackWithStems>>

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeWithStems(id: Long): Flow<TrackWithStems?>

    @Query("SELECT * FROM tracks WHERE separationState IN (:states) ORDER BY addedAt ASC")
    suspend fun byState(states: List<SeparationState>): List<Track>

    @Query("UPDATE tracks SET separationState = :state, separationProgress = :progress, separationError = :error WHERE id = :id")
    suspend fun setSeparationState(
        id: Long,
        state: SeparationState,
        progress: Float,
        error: String?,
    )

    @Query("UPDATE tracks SET separationProgress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    /**
     * RUNNING is only ever true while a worker is alive. If the process died mid-separation the
     * row is stale, so the repository resets these on startup.
     */
    @Query("UPDATE tracks SET separationState = 'PENDING', separationProgress = 0 WHERE separationState = 'RUNNING'")
    suspend fun resetStalledSeparations()
}

@Dao
interface StemSetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stemSet: StemSet): Long

    @Query("SELECT * FROM stem_sets WHERE trackId = :trackId")
    suspend fun byTrack(trackId: Long): StemSet?

    @Query("DELETE FROM stem_sets WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: Long)

    /**
     * Writes a beat grid onto stems that already exist.
     *
     * Separation is the expensive part of this app, and the grid is measured from its output rather
     * than produced by it — so a track separated before the detector existed can be given one
     * without doing the minutes of work again.
     */
    @Query("UPDATE stem_sets SET bpm = :bpm, firstBeatFrame = :firstBeatFrame WHERE trackId = :trackId")
    suspend fun setBeatGrid(trackId: Long, bpm: Float, firstBeatFrame: Long)
}

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Query("SELECT * FROM recordings ORDER BY slot ASC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldest(): Recording?

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    @Query("SELECT slot FROM recordings")
    suspend fun occupiedSlots(): List<Int>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}
