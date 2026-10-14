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
    version = 4,
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

        /**
         * The backend dropped the tag `name` field: a tag is now identified solely by its `slug`,
         * which is also its display label. Recreate the cached `tags` table without the `name`
         * column; it is a pure server cache and repopulates on the next tag refresh. The `name`
         * keys still embedded in existing `notes`/`conflicts` tag JSON are harmless — Gson ignores
         * the unknown field when deserializing into the slimmed-down [TagEntity].
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `tags`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`slug` TEXT NOT NULL, PRIMARY KEY(`slug`))")
            }
        }

        /**
         * Recover validators from untouched server timestamps, normalizing legacy seconds to
         * milliseconds just as the server migration does. Locally edited timestamps cannot
         * recover the base validator: keep numeric strings for pending edits so they receive
         * 412, preserving both copies for explicit conflict resolution.
         * Empty validators identify unsynced creates only.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE `notes_new` (`slug` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `excerpt` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `etag` TEXT NOT NULL, `hasFullContent` INTEGER NOT NULL, `isPendingDelete` INTEGER NOT NULL, `tags` TEXT NOT NULL, PRIMARY KEY(`slug`))""")
                db.execSQL("""INSERT INTO `notes_new` SELECT `slug`, `title`, `content`, `excerpt`, `createdAt`, `updatedAt`, CASE WHEN `version` = 0 THEN '' WHEN NOT EXISTS (SELECT 1 FROM pending_changes WHERE pending_changes.slug = notes.slug) THEN COALESCE('"' || strftime('%Y-%m-%dT%H:%M:%fZ', updatedAt) || '"', '"' || `version` || '"') ELSE '"' || `version` || '"' END, `hasFullContent`, `isPendingDelete`, `tags` FROM `notes`""")
                db.execSQL("""DROP TABLE `notes`""")
                db.execSQL("""ALTER TABLE `notes_new` RENAME TO `notes`""")
                db.execSQL("""CREATE TABLE `pending_changes_new` (`changeId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `slug` TEXT NOT NULL, `changeType` TEXT NOT NULL, `baseEtag` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `status` TEXT NOT NULL)""")
                db.execSQL("""INSERT INTO `pending_changes_new` SELECT `changeId`, `slug`, `changeType`, CASE WHEN `baseVersion` = 0 THEN '' ELSE '"' || `baseVersion` || '"' END, `timestamp`, `status` FROM `pending_changes`""")
                db.execSQL("""DROP TABLE `pending_changes`""")
                db.execSQL("""ALTER TABLE `pending_changes_new` RENAME TO `pending_changes`""")
                db.execSQL("""CREATE TABLE `conflicts_new` (`slug` TEXT NOT NULL, `localTitle` TEXT NOT NULL, `localContent` TEXT NOT NULL, `localTags` TEXT NOT NULL, `localBaseEtag` TEXT NOT NULL, `serverTitle` TEXT NOT NULL, `serverContent` TEXT NOT NULL, `serverTags` TEXT NOT NULL, `serverEtag` TEXT NOT NULL, `serverUpdatedAt` TEXT NOT NULL, `detectedAt` INTEGER NOT NULL, PRIMARY KEY(`slug`))""")
                db.execSQL("""INSERT INTO `conflicts_new` SELECT `slug`, `localTitle`, `localContent`, `localTags`, CASE WHEN `localBaseVersion` = 0 THEN '' ELSE '"' || `localBaseVersion` || '"' END, `serverTitle`, `serverContent`, `serverTags`, COALESCE('"' || strftime('%Y-%m-%dT%H:%M:%fZ', serverUpdatedAt) || '"', '"' || `serverVersion` || '"'), `serverUpdatedAt`, `detectedAt` FROM `conflicts`""")
                db.execSQL("""DROP TABLE `conflicts`""")
                db.execSQL("""ALTER TABLE `conflicts_new` RENAME TO `conflicts`""")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mynotes_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
        }
    }
}
