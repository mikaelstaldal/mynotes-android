package nu.staldal.mynotes.ui.note

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
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
    var showNoteLinkPicker by remember { mutableStateOf(false) }
    var showTagLinkPicker by remember { mutableStateOf(false) }

    fun insertAtCursor(text: String) {
        val insertPos = contentField.selection.start.coerceIn(0, contentField.text.length)
        val newText = contentField.text.substring(0, insertPos) + text + contentField.text.substring(insertPos)
        contentField = TextFieldValue(newText, selection = TextRange(insertPos + text.length))
        viewModel.updateFormContent(newText)
    }

    // Insert a `[[...]]` wikilink, prepending a blank line when the cursor sits directly after a raw
    // HTML element (e.g. an SVG or MathML block). Without the separating blank line CommonMark keeps
    // consuming the following line as part of the HTML block, so the link would render as literal
    // text instead of a wikilink (see WikiLinkProcessor).
    fun insertLink(linkText: String) {
        val insertPos = contentField.selection.start.coerceIn(0, contentField.text.length)
        insertAtCursor(wikiLinkBlankLinePrefix(contentField.text.substring(0, insertPos)) + linkText)
    }

    LaunchedEffect(slug) {
        viewModel.loadNoteForEdit(slug)
    }
    LaunchedEffect(state.content) {
        if (contentField.text != state.content) {
            contentField = TextFieldValue(state.content, selection = TextRange(state.content.length))
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
                insertAtCursor(placeholder)
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
                    IconButton(onClick = { showNoteLinkPicker = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Link to note")
                    }
                    IconButton(onClick = { showTagLinkPicker = true }) {
                        Icon(Icons.Default.Tag, contentDescription = "Link to tag")
                    }
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
                .consumeWindowInsets(padding)
                .imePadding()
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
                onCreate = { slug -> viewModel.createAndAttachTag(slug); showNewTagDialog = false },
            )
        }

        if (showNoteLinkPicker) {
            WikiLinkPickerDialog(
                title = "Link to note",
                options = state.availableNotes
                    .filter { it.slug != state.slug }
                    .map { WikiLinkOption(slug = it.slug, label = it.title) },
                onDismiss = { showNoteLinkPicker = false },
                onSelect = { insertLink("[[${it.slug}]]"); showNoteLinkPicker = false },
            )
        }

        if (showTagLinkPicker) {
            WikiLinkPickerDialog(
                title = "Link to tag",
                options = state.availableTags.map { WikiLinkOption(slug = it.slug, label = it.slug) },
                onDismiss = { showTagLinkPicker = false },
                onSelect = { insertLink("[[#${it.slug}]]"); showTagLinkPicker = false },
            )
        }
    }
}

// Matches text that ends with a raw HTML/SVG/MathML tag (opening, closing or self-closing), e.g.
// `</svg>`, `<math>`, `<rect x="1"/>`. The tag name must be followed by whitespace, `/` or `>` so
// URL autolinks like `<https://example.com>` are not mistaken for tags.
private val HTML_TAG_AT_END = Regex("""</?[A-Za-z][A-Za-z0-9-]*(\s[^<>]*)?/?>$""")

// Returns the newlines to prepend to a wikilink inserted at the cursor so that a raw HTML element
// immediately before it is separated by a blank line. Returns "" when no separation is needed —
// either there is no preceding HTML element or a blank line already exists.
internal fun wikiLinkBlankLinePrefix(before: String): String {
    val content = before.trimEnd()
    if (content.isEmpty() || !HTML_TAG_AT_END.containsMatchIn(content)) return ""
    return when (before.substring(content.length).count { it == '\n' }) {
        0 -> "\n\n" // cursor on the same line as the element
        1 -> "\n"   // cursor on the next line; one more newline makes the line blank
        else -> ""  // already separated by a blank line
    }
}

private data class WikiLinkOption(val slug: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiLinkPickerDialog(
    title: String,
    options: List<WikiLinkOption>,
    onDismiss: () -> Unit,
    onSelect: (WikiLinkOption) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        if (query.isBlank()) {
            options
        } else {
            options.filter { it.label.startsWith(query, ignoreCase = true) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.imePadding(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text("No matches", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.slug }) { option ->
                            ListItem(
                                headlineContent = { Text(option.label) },
                                supportingContent = if (option.label != option.slug) {
                                    { Text(option.slug) }
                                } else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
            FilterChip(selected = selected, onClick = { onToggle(tag) }, label = { Text(tag.slug) })
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
    var slug by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Tag") },
        text = {
            OutlinedTextField(
                value = slug,
                onValueChange = { slug = it },
                label = { Text("Slug") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(slug) }, enabled = slug.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
