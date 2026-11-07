package nu.staldal.mynotes.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nu.staldal.mynotes.dataStore

data class ServerConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val offlineMode: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() || offlineMode
}

@Singleton
class UserPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore,
) {

    companion object {
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
    }

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            baseUrl = credentialStore.baseUrl ?: "",
            username = credentialStore.username ?: "",
            password = credentialStore.password ?: "",
            offlineMode = prefs[OFFLINE_MODE] ?: false,
        )
    }

    suspend fun saveServerConfig(baseUrl: String, username: String, password: String) {
        credentialStore.save(baseUrl, username, password)
        context.dataStore.edit { prefs ->
            prefs[OFFLINE_MODE] = false
        }
    }

    suspend fun saveOfflineMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[OFFLINE_MODE] = enabled
        }
    }

    /** Wipes stored credentials and resets all app preferences to their defaults. */
    suspend fun clearAll() {
        credentialStore.clear()
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
