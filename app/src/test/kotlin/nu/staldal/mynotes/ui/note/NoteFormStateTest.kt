package nu.staldal.mynotes.ui.note

import nu.staldal.mynotes.data.local.TagEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [NoteFormState.isDirty], which is what keeps the form's Save button disabled until the
 * user has actually changed something.
 */
class NoteFormStateTest {

    private fun loaded(title: String, content: String, vararg tags: String) = NoteFormState(
        slug = "a-note",
        title = title,
        content = content,
        tags = tags.map { TagEntity(it) },
        loadedTitle = title,
        loadedContent = content,
        loadedTagSlugs = tags.toSet(),
    )

    @Test
    fun `an empty new note is not dirty`() {
        assertFalse(NoteFormState().isDirty)
    }

    @Test
    fun `a new note with a title is dirty`() {
        assertTrue(NoteFormState(title = "Hello").isDirty)
    }

    @Test
    fun `a new note with content is dirty`() {
        assertTrue(NoteFormState(content = "Hello").isDirty)
    }

    @Test
    fun `a new note with a tag is dirty`() {
        assertTrue(NoteFormState(tags = listOf(TagEntity("work"))).isDirty)
    }

    @Test
    fun `a freshly loaded note is not dirty`() {
        assertFalse(loaded("Title", "Body", "work", "todo").isDirty)
    }

    @Test
    fun `changing the title makes it dirty`() {
        assertTrue(loaded("Title", "Body").copy(title = "Other").isDirty)
    }

    @Test
    fun `changing the content makes it dirty`() {
        assertTrue(loaded("Title", "Body").copy(content = "Body!").isDirty)
    }

    @Test
    fun `adding a tag makes it dirty`() {
        val state = loaded("Title", "Body", "work")
        assertTrue(state.copy(tags = state.tags + TagEntity("todo")).isDirty)
    }

    @Test
    fun `removing a tag makes it dirty`() {
        val state = loaded("Title", "Body", "work", "todo")
        assertTrue(state.copy(tags = state.tags.filterNot { it.slug == "todo" }).isDirty)
    }

    @Test
    fun `reordering the tags does not make it dirty`() {
        val state = loaded("Title", "Body", "work", "todo")
        assertFalse(state.copy(tags = state.tags.reversed()).isDirty)
    }

    @Test
    fun `undoing an edit makes it clean again`() {
        val state = loaded("Title", "Body")
        assertFalse(state.copy(title = "Other").copy(title = "Title").isDirty)
    }

    @Test
    fun `loading a note is unaffected by the tags available to pick from`() {
        val state = loaded("Title", "Body", "work")
        assertFalse(state.copy(availableTags = listOf(TagEntity("work"), TagEntity("todo"))).isDirty)
    }
}
