package nu.staldal.mynotes.ui.note

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.data.ALLOWED_ARTIFACT_CONTENT_TYPES
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.preferences.ServerConfig
import nu.staldal.mynotes.data.preferences.UserPreferences
import nu.staldal.mynotes.data.sync.SyncWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NoteDetailState(
    val slug: String? = null,
    val title: String = "",
    val content: String = "",
    val updatedAt: String = "",
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val error: String? = null,
)

data class NoteFormState(
    val slug: String? = null,
    val title: String = "",
    val content: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = UserPreferences(application)
    private val database = (application as MyNotesApplication).database

    private val _detailState = MutableStateFlow(NoteDetailState())
    val detailState: StateFlow<NoteDetailState> = _detailState.asStateFlow()

    private val _formState = MutableStateFlow(NoteFormState())
    val formState: StateFlow<NoteFormState> = _formState.asStateFlow()

    private var serverConfig = ServerConfig()

    val artifactRepository = ArtifactRepository(application, database) {
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
            prefs.serverConfig.collect { serverConfig = it }
        }
    }

    fun loadNote(slug: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.ensureFullContent(slug)
                val note = repository.getNote(slug)
                if (note != null) {
                    _detailState.update {
                        it.copy(
                            slug = note.slug,
                            title = note.title,
                            content = note.content,
                            updatedAt = note.updatedAt,
                            isLoading = false,
                        )
                    }
                } else {
                    _detailState.update { it.copy(isLoading = false, error = "Note not found") }
                }
            } catch (e: Exception) {
                _detailState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun deleteNote(slug: String) {
        viewModelScope.launch {
            _detailState.update { it.copy(isDeleting = true, error = null) }
            try {
                repository.deleteNote(slug)
                _detailState.update { it.copy(isDeleted = true, isDeleting = false) }
                SyncWorker.enqueueOneTime(getApplication())
            } catch (e: Exception) {
                _detailState.update { it.copy(isDeleting = false, error = e.message) }
            }
        }
    }

    fun loadNoteForEdit(slug: String?) {
        if (slug == null) {
            _formState.update { NoteFormState() }
            return
        }
        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true) }
            try {
                val note = repository.getNote(slug)
                if (note != null) {
                    _formState.update {
                        it.copy(slug = note.slug, title = note.title, content = note.content, isLoading = false)
                    }
                } else {
                    _formState.update { it.copy(isLoading = false, error = "Note not found") }
                }
            } catch (e: Exception) {
                _formState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun updateFormTitle(value: String) { _formState.update { it.copy(title = value) } }
    fun updateFormContent(value: String) { _formState.update { it.copy(content = value) } }

    fun insertImagePlaceholder(uri: Uri, contentType: String, onInserted: (String) -> Unit) {
        if (contentType !in ALLOWED_ARTIFACT_CONTENT_TYPES) {
            _formState.update { it.copy(error = "Unsupported image type: $contentType") }
            return
        }
        viewModelScope.launch {
            try {
                val ownerSlug = _formState.value.slug ?: "unsaved"
                val localId = artifactRepository.attachLocalImage(ownerSlug, uri, contentType)
                onInserted("![image](local-artifact://$localId)")
            } catch (e: Exception) {
                _formState.update { it.copy(error = "Could not attach image: ${e.message}") }
            }
        }
    }

    fun saveNote() {
        val form = _formState.value
        if (form.title.isBlank()) {
            _formState.update { it.copy(error = "Title is required") }
            return
        }
        viewModelScope.launch {
            _formState.update { it.copy(isSaving = true, error = null) }
            try {
                if (form.slug == null) {
                    repository.createNote(form.title, form.content)
                } else {
                    repository.updateNote(form.slug, form.title, form.content)
                }
                _formState.update { it.copy(isSaving = false, isSaved = true) }
                SyncWorker.enqueueOneTime(getApplication())
            } catch (e: Exception) {
                _formState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
