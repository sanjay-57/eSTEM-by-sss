package com.sss.estem.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sss.estem.data.model.SeparationState

class Converters {
    @TypeConverter
    fun toSeparationState(value: String): SeparationState = SeparationState.valueOf(value)

    @TypeConverter
    fun fromSeparationState(state: SeparationState): String = state.name
}

@Database(
    entities = [Track::class, StemSet::class, Recording::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EstemDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun stemSetDao(): StemSetDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        /**
         * Adds the beat grid to existing stem sets.
         *
         * Migrated rather than rebuilt: separation is the expensive part of this app, and dropping
         * the table would make every already-separated track do it again to gain a column that
         * defaults to "unknown" anyway. Existing rows land on 0 bpm, which reads as "no grid" — and
         * re-separating a track fills it in.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stem_sets ADD COLUMN bpm REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stem_sets ADD COLUMN firstBeatFrame INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): EstemDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EstemDatabase::class.java,
                "estem.db",
            ).addMigrations(MIGRATION_1_2).build()
    }
}
