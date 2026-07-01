package nu.staldal.mynotes.ui.conflict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.ConflictEntity
import nu.staldal.mynotes.data.preferences.ServerConfig
import nu.staldal.mynotes.data.preferences.UserPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConflictViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = UserPreferences(application)
    private val database = (application as MyNotesApplication).database

    private var serverConfig = ServerConfig()

    private val artifactRepository = ArtifactRepository(application, database) {
        RetrofitClient.getApiService(serverConfig.baseUrl, serverConfig.username, serverConfig.password)
    }

    private val repository = NoteRepository(
        database = database,
        artifactRepository = artifactRepository,
        apiProvider = { RetrofitClient.getApiService(serverConfig.baseUrl, serverConfig.username, serverConfig.password) },
        baseUrlProvider = { serverConfig.baseUrl },
    )

    val conflicts: StateFlow<List<ConflictEntity>> = repository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            prefs.serverConfig.collect { serverConfig = it }
        }
    }

    fun resolve(slug: String, keepLocal: Boolean) {
        viewModelScope.launch {
            repository.resolveConflict(slug, keepLocal)
        }
    }
}
