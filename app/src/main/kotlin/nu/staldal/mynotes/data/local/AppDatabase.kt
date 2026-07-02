package nu.staldal.mynotes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Greenfield schema, version 1. Future schema changes must bump [version], add an explicit
 * [androidx.room.migration.Migration], and register it via `.addMigrations(...)` below —
 * never edit an already-shipped migration.
 */
@Database(
    entities = [NoteEntity::class, PendingChange::class, ConflictEntity::class, ArtifactEntity::class, TagEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun pendingChangeDao(): PendingChangeDao
    abstract fun conflictDao(): ConflictDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`slug` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`slug`))")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `conflicts` ADD COLUMN `localTags` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `conflicts` ADD COLUMN `serverTags` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mynotes_database"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
