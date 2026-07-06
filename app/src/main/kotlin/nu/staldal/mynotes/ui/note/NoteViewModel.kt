package nu.staldal.mynotes.ui.note

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.data.ALLOWED_ARTIFACT_CONTENT_TYPES
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.ConnectivityObserver
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.NoteEntity
import nu.staldal.mynotes.data.local.TagEntity
import nu.staldal.mynotes.data.preferences.ServerConfig
import nu.staldal.mynotes.data.preferences.UserPreferences
import nu.staldal.mynotes.data.sync.SyncWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NoteDetailState(
    val slug: String? = null,
    val title: String = "",
    val content: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val version: Int = 0,
    val tags: List<TagEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val error: String? = null,
)

data class NoteFormState(
    val slug: String? = null,
    val title: String = "",
    val content: String = "",
    val tags: List<TagEntity> = emptyList(),
    val availableTags: List<TagEntity> = emptyList(),
    val availableNotes: List<NoteEntity> = emptyList(),
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

    private val connectivityObserver = ConnectivityObserver(application)

    private var serverConfig = ServerConfig()
    private var networkAvailable = true

    /** True when the backend can actually be reached (network up and not in explicit offline mode). */
    private fun canReachBackend(): Boolean = networkAvailable && !serverConfig.offlineMode

    val artifactRepository = ArtifactRepository(
        application,
        database,
        isOnlineProvider = { canReachBackend() },
    ) {
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
        viewModelScope.launch {
            connectivityObserver.isOnline.collect { networkAvailable = it }
        }
        viewModelScope.launch {
            repository.observeTags().collect { tags ->
                _formState.update { it.copy(availableTags = tags) }
            }
        }
        viewModelScope.launch {
            repository.observeNotes().collect { notes ->
                _formState.update { it.copy(availableNotes = notes) }
            }
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
                            createdAt = note.createdAt,
                            updatedAt = note.updatedAt,
                            version = note.version,
                            tags = note.tags,
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
            _formState.update { NoteFormState(availableTags = it.availableTags, availableNotes = it.availableNotes) }
            return
        }
        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true) }
            try {
                val note = repository.getNote(slug)
                if (note != null) {
                    _formState.update {
                        it.copy(slug = note.slug, title = note.title, content = note.content, tags = note.tags, isLoading = false)
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

    fun toggleFormTag(tag: TagEntity) {
        _formState.update {
            val tags = if (it.tags.any { t -> t.slug == tag.slug }) {
                it.tags.filterNot { t -> t.slug == tag.slug }
            } else {
                it.tags + tag
            }
            it.copy(tags = tags)
        }
    }

    /** Creates a new tag on the server and attaches it to the note being edited. Requires connectivity. */
    fun createAndAttachTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val tag = repository.createTag(name)
                _formState.update { it.copy(tags = it.tags + tag) }
            } catch (e: Exception) {
                _formState.update { it.copy(error = "Could not create tag: ${e.message}") }
            }
        }
    }

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
                    repository.createNote(form.title, form.content, form.tags)
                } else {
                    repository.updateNote(form.slug, form.title, form.content, form.tags)
                }
                _formState.update { it.copy(isSaving = false, isSaved = true) }
                SyncWorker.enqueueOneTime(getApplication())
            } catch (e: Exception) {
                _formState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
