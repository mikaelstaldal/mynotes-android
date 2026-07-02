package nu.staldal.mynotes.ui.note

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mynotes.data.local.TagEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteFormScreen(
    slug: String?,
    onNavigateBack: () -> Unit,
    viewModel: NoteViewModel = viewModel(),
) {
    val state by viewModel.formState.collectAsState()
    val context = LocalContext.current
    var contentField by remember { mutableStateOf(TextFieldValue("")) }
    var showNewTagDialog by remember { mutableStateOf(false) }

    LaunchedEffect(slug) {
        viewModel.loadNoteForEdit(slug)
    }
    LaunchedEffect(state.content) {
        if (contentField.text != state.content) {
            contentField = TextFieldValue(state.content, selection = androidx.compose.ui.text.TextRange(state.content.length))
        }
    }
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onNavigateBack()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val contentType = context.contentResolver.getType(uri) ?: return@rememberLauncherForActivityResult
            viewModel.insertImagePlaceholder(uri, contentType) { placeholder ->
                val insertPos = contentField.selection.start.coerceIn(0, contentField.text.length)
                val newText = contentField.text.substring(0, insertPos) + placeholder + contentField.text.substring(insertPos)
                contentField = TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(insertPos + placeholder.length))
                viewModel.updateFormContent(newText)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (slug == null) "New Note" else "Edit Note") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.Image, contentDescription = "Insert image")
                    }
                    TextButton(onClick = { viewModel.saveNote() }, enabled = !state.isSaving) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = { viewModel.updateFormTitle(it) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TagPickerRow(
                availableTags = state.availableTags,
                selectedTags = state.tags,
                onToggle = { viewModel.toggleFormTag(it) },
                onAddNew = { showNewTagDialog = true },
            )
            OutlinedTextField(
                value = contentField,
                onValueChange = {
                    contentField = it
                    viewModel.updateFormContent(it.text)
                },
                label = { Text("Content (Markdown)") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (showNewTagDialog) {
            NewTagDialog(
                onDismiss = { showNewTagDialog = false },
                onCreate = { name -> viewModel.createAndAttachTag(name); showNewTagDialog = false },
            )
        }
    }
}

@Composable
private fun TagPickerRow(
    availableTags: List<TagEntity>,
    selectedTags: List<TagEntity>,
    onToggle: (TagEntity) -> Unit,
    onAddNew: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(availableTags) { tag ->
            val selected = selectedTags.any { it.slug == tag.slug }
            FilterChip(selected = selected, onClick = { onToggle(tag) }, label = { Text(tag.name) })
        }
        item {
            AssistChip(
                onClick = onAddNew,
                label = { Text("New tag") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTagDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Tag") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
