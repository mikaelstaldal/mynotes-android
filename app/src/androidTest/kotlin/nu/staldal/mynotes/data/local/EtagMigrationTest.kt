package nu.staldal.mynotes.data.local

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class EtagMigrationTest {
    @Test
    fun migrationPreservesPendingEditsAndConflicts() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(InstrumentationRegistry.getInstrumentation().targetContext)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE notes (slug TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, excerpt TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, version INTEGER NOT NULL, hasFullContent INTEGER NOT NULL, isPendingDelete INTEGER NOT NULL, tags TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE pending_changes (changeId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, slug TEXT NOT NULL, changeType TEXT NOT NULL, baseVersion INTEGER NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE conflicts (slug TEXT PRIMARY KEY NOT NULL, localTitle TEXT NOT NULL, localContent TEXT NOT NULL, localTags TEXT NOT NULL, localBaseVersion INTEGER NOT NULL, serverTitle TEXT NOT NULL, serverContent TEXT NOT NULL, serverTags TEXT NOT NULL, serverVersion INTEGER NOT NULL, serverUpdatedAt TEXT NOT NULL, detectedAt INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO notes VALUES ('edit', 'Title', 'Local body', '', 'created', 'locally edited', 12, 1, 0, '[]')")
            db.execSQL("INSERT INTO notes VALUES ('new', 'New', 'Unsynced', '', 'created', 'updated', 0, 1, 0, '[]')")
            db.execSQL("INSERT INTO pending_changes VALUES (9, 'edit', 'UPDATE', 12, 100, 'CONFLICT')")
            db.execSQL("INSERT INTO pending_changes VALUES (10, 'new', 'CREATE', 0, 101, 'PENDING')")
            db.execSQL("INSERT INTO conflicts VALUES ('edit', 'Title', 'Local body', '[]', 12, 'Remote', 'Remote body', '[]', 13, '2026-10-14T08:00:00Z', 102)")
            db.execSQL("INSERT INTO notes VALUES ('clean', 'Clean', 'Cached', '', 'created', '2026-10-14T08:00:00Z', 14, 1, 0, '[]')")
            AppDatabase.MIGRATION_3_4.migrate(db)
            db.query("SELECT etag FROM notes WHERE slug = 'clean'").use {
                it.moveToFirst(); assertEquals("\"2026-10-14T08:00:00.000Z\"", it.getString(0))
            }
            db.query("SELECT etag, content FROM notes WHERE slug = 'edit'").use {
                it.moveToFirst(); assertEquals("\"12\"", it.getString(0)); assertEquals("Local body", it.getString(1))
            }
            db.query("SELECT etag FROM notes WHERE slug = 'new'").use {
                it.moveToFirst(); assertEquals("", it.getString(0))
            }
            db.query("SELECT changeId, baseEtag, status FROM pending_changes ORDER BY changeId").use {
                it.moveToFirst(); assertEquals(9L, it.getLong(0)); assertEquals("\"12\"", it.getString(1)); assertEquals("CONFLICT", it.getString(2))
                it.moveToNext(); assertEquals("", it.getString(1)); assertEquals("PENDING", it.getString(2))
            }
            db.query("SELECT localBaseEtag, serverEtag, serverContent FROM conflicts").use {
                it.moveToFirst(); assertEquals("\"12\"", it.getString(0)); assertEquals("\"2026-10-14T08:00:00.000Z\"", it.getString(1)); assertEquals("Remote body", it.getString(2))
            }
        } finally {
            helper.close()
        }
    }
}
