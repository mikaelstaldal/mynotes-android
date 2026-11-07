package nu.staldal.mynotes.ui.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.local.ConflictEntity

@HiltViewModel
class ConflictViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {
    val conflicts: StateFlow<List<ConflictEntity>> = repository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun resolve(slug: String, keepLocal: Boolean) {
        viewModelScope.launch {
            repository.resolveConflict(slug, keepLocal)
        }
    }
}
