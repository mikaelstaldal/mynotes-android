package nu.staldal.mynotes.ui.note

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.ConnectivityObserver
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.NoteEntity
import nu.staldal.mynotes.data.local.TagEntity
import nu.staldal.mynotes.data.preferences.ServerConfig
import nu.staldal.mynotes.data.preferences.UserPreferences
import nu.staldal.mynotes.data.sync.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NoteListUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val isOfflineMode: Boolean = false,
    val isConfigured: Boolean = false,
    val pendingChangesCount: Int = 0,
    val conflictCount: Int = 0,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<NoteEntity> = emptyList(),
    val availableTags: List<TagEntity> = emptyList(),
    val selectedTag: String? = null,
    val error: String? = null,
    val syncMessage: String? = null,
)

fun List<NoteEntity>.filterByTag(tag: String?): List<NoteEntity> =
    if (tag == null) this else filter { note -> note.tags.any { it.slug == tag } }

class NoteListViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = UserPreferences(application)
    private val database = (application as MyNotesApplication).database
    private val connectivityObserver = ConnectivityObserver(application)
    private val _uiState = MutableStateFlow(NoteListUiState())
    val uiState: StateFlow<NoteListUiState> = _uiState.asStateFlow()

    private var serverConfig = ServerConfig()
    private var searchJob: Job? = null

    private val artifactRepository = ArtifactRepository(application, database) {
        RetrofitClient.getApiService(serverConfig.baseUrl, serverConfig.username, serverConfig.password)
    }

    private val repository = NoteRepository(
        database = database,
        artifactRepository = artifactRepository,
        apiProvider = { RetrofitClient.getApiService(serverConfig.baseUrl, serverConfig.username, serverConfig.password) },
        baseUrlProvider = { serverConfig.baseUrl },
    )

    init {
        viewModelScope.launch {
            repository.observeNotes().collect { notes ->
                _uiState.update { it.copy(notes = notes) }
            }
        }

        viewModelScope.launch {
            connectivityObserver.isOnline.collect { online ->
                val wasOffline = !_uiState.value.isOnline
                _uiState.update { it.copy(isOnline = online, error = if (!online) null else it.error) }
                if (online && wasOffline && _uiState.value.isConfigured && !_uiState.value.isOfflineMode) {
                    syncNow()
                }
            }
        }

        viewModelScope.launch {
            repository.getPendingChangeCount().collect { count ->
                _uiState.update { it.copy(pendingChangesCount = count) }
            }
        }

        viewModelScope.launch {
            repository.getConflictCount().collect { count ->
                _uiState.update { it.copy(conflictCount = count) }
            }
        }

        viewModelScope.launch {
            repository.observeTags().collect { tags ->
                _uiState.update { it.copy(availableTags = tags) }
            }
        }

        viewModelScope.launch {
            prefs.serverConfig.collect { config ->
                serverConfig = config
                _uiState.update { it.copy(isConfigured = config.isConfigured, isOfflineMode = config.offlineMode) }
                if (config.isConfigured && !config.offlineMode) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isOfflineMode || !_uiState.value.isOnline) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.refreshNotes()
                repository.refreshTags()
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun syncNow() {
        if (_uiState.value.isOfflineMode) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val notifications = repository.syncPendingChanges()
                repository.refreshNotes()
                repository.refreshTags()
                _uiState.update {
                    it.copy(isLoading = false, syncMessage = notifications.firstOrNull() ?: "Synced")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectTag(slug: String?) {
        _uiState.update { it.copy(selectedTag = if (it.selectedTag == slug) null else slug) }
    }

    fun deleteTag(slug: String) {
        viewModelScope.launch {
            try {
                repository.deleteTag(slug)
                if (_uiState.value.selectedTag == slug) _uiState.update { it.copy(selectedTag = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                val results = repository.search(query, online = _uiState.value.isOnline && !_uiState.value.isOfflineMode)
                _uiState.update { it.copy(isSearching = false, searchResults = results) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false, searchResults = emptyList(), error = e.message) }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = "", isSearching = false, searchResults = emptyList()) }
    }

    fun enableOfflineMode() {
        viewModelScope.launch {
            prefs.saveOfflineMode(true)
        }
    }

    fun clearSyncMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }
}
