package nu.staldal.mynotes.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.preferences.UserPreferences
import nu.staldal.mynotes.data.sync.SyncWorker

data class SettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: String? = null,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val isSigningOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    private val database: AppDatabase,
    private val client: RetrofitClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.serverConfig.first().let { config ->
                _uiState.update {
                    it.copy(baseUrl = config.baseUrl, username = config.username, password = config.password)
                }
            }
        }
    }

    fun updateBaseUrl(url: String) { _uiState.update { it.copy(baseUrl = url) } }
    fun updateUsername(username: String) { _uiState.update { it.copy(username = username) } }
    fun updatePassword(password: String) { _uiState.update { it.copy(password = password) } }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            prefs.saveServerConfig(state.baseUrl, state.username, state.password)
            _uiState.update { it.copy(isSaving = false) }
            onSaved()
        }
    }

    /**
     * Signs out and wipes all local state: cancels background sync, clears stored credentials and
     * preferences, empties the Room database, and deletes the cached artifact and share files.
     */
    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSigningOut = true) }
            val app = context
            SyncWorker.cancelAll(app)
            prefs.clearAll()
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                File(app.filesDir, "artifacts").deleteRecursively()
                File(app.cacheDir, "shared").deleteRecursively()
            }
            client.reset()
            _uiState.value = SettingsUiState()
            onSignedOut()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            try {
                val state = _uiState.value
                val api = client.getApiService(state.baseUrl, state.username, state.password)
                if (api == null) {
                    _uiState.update { it.copy(isTesting = false, testResult = "Invalid server URL") }
                    return@launch
                }
                val response = api.listNotes(q = null, limit = 1, offset = 0)
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isTesting = false, testResult = "Connection successful!") }
                } else {
                    _uiState.update { it.copy(isTesting = false, testResult = "Error: ${response.code()} ${response.message()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isTesting = false, testResult = "Error: ${e.message}") }
            }
        }
    }
}
