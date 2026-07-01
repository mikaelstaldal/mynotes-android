package nu.staldal.mynotes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Greenfield schema, version 1. Future schema changes must bump [version], add an explicit
 * [androidx.room.migration.Migration], and register it via `.addMigrations(...)` below —
 * never edit an already-shipped migration.
 */
@Database(
    entities = [NoteEntity::class, PendingChange::class, ConflictEntity::class, ArtifactEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun pendingChangeDao(): PendingChangeDao
    abstract fun conflictDao(): ConflictDao
    abstract fun artifactDao(): ArtifactDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mynotes_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
