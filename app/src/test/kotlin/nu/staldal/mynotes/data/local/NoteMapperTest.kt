package nu.staldal.mynotes.data.local

import org.junit.Assert.*
import org.junit.Test

class NoteMapperTest {
    private val timestamp = "2026-10-14T08:00:00.123Z"
    private fun note(etag: String, full: Boolean) = NoteEntity(
        slug = "test", title = "Test", content = if (full) "Body" else "",
        excerpt = "", createdAt = timestamp, updatedAt = timestamp,
        etag = etag, hasFullContent = full,
    )

    @Test
    fun `timestamp validator retains quotes and millisecond precision`() {
        assertEquals("\"2026-10-14T08:00:00.123Z\"", timestampEtag(timestamp))
    }

    @Test
    fun `missing weakened or rewritten headers use the canonical timestamp`() {
        val canonical = timestampEtag(timestamp)
        for (header in listOf(null, "W/$canonical", "\"unrelated\"", canonical)) {
            assertEquals(canonical, responseEtag(header, timestamp))
        }
    }

    @Test
    fun `same timestamp retains full cached content`() {
        val etag = timestampEtag(timestamp)
        val merged = note(etag, false).mergeContentFrom(note(etag, true))
        assertTrue(merged.hasFullContent)
        assertEquals("Body", merged.content)
    }

    @Test
    fun `a millisecond change invalidates cached content`() {
        val merged = note(timestampEtag("2026-10-14T08:00:00.124Z"), false)
            .mergeContentFrom(note(timestampEtag(timestamp), true))
        assertFalse(merged.hasFullContent)
        assertEquals("", merged.content)
    }

    @Test
    fun `legacy numeric validator invalidates cached content`() {
        assertFalse(note(timestampEtag(timestamp), false).mergeContentFrom(note("\"12\"", true)).hasFullContent)
    }
}
