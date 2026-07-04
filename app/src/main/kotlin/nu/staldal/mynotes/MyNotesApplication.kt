package nu.staldal.mynotes

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.sync.SyncWorker
import java.io.File

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MyNotesApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // Drop any note content left in the share cache from a previous run.
        File(cacheDir, "shared").deleteRecursively()
        SyncWorker.enqueuePeriodic(this)
    }
}
