package nu.staldal.mynotes.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.preferences.UserPreferences
import nu.staldal.mynotes.data.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: String? = null,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val isSigningOut: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = UserPreferences(application)
    private val database = (application as MyNotesApplication).database
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
            val app = getApplication<Application>()
            SyncWorker.cancelAll(app)
            prefs.clearAll()
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                File(app.filesDir, "artifacts").deleteRecursively()
                File(app.cacheDir, "shared").deleteRecursively()
            }
            RetrofitClient.reset()
            _uiState.value = SettingsUiState()
            onSignedOut()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            try {
                val state = _uiState.value
                val api = RetrofitClient.getApiService(state.baseUrl, state.username, state.password)
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
