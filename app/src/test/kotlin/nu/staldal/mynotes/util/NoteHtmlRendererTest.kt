package nu.staldal.mynotes.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [NoteHtmlRenderer] end-to-end (commonmark parse + render + OWASP sanitize) with a
 * focus on GFM task lists: the `- [ ]`/`- [x]` markers must become disabled checkboxes, and the
 * sanitizer must keep those checkboxes while rejecting every other form control — mirroring the
 * server (mynotes/internal/service/markdown.go) and web client (mynotes/web/ts/util/markdown.ts).
 */
class NoteHtmlRendererTest {
    private fun render(markdown: String): String = NoteHtmlRenderer.renderToSanitizedHtml(markdown)

    @Test
    fun `unchecked task item renders a disabled checkbox`() {
        val html = render("- [ ] todo")
        assertTrue(html, html.contains("<input"))
        assertTrue(html, html.contains("checkbox"))
        assertTrue(html, html.contains("disabled"))
        assertFalse(html, html.contains("checked"))
    }

    @Test
    fun `checked task item renders a checked disabled checkbox`() {
        val html = render("- [x] done")
        assertTrue(html, html.contains("<input"))
        assertTrue(html, html.contains("checkbox"))
        assertTrue(html, html.contains("disabled"))
        assertTrue(html, html.contains("checked"))
    }

    @Test
    fun `uppercase checked marker is treated as checked`() {
        val html = render("- [X] done")
        assertTrue(html, html.contains("checked"))
    }

    @Test
    fun `raw text input is dropped by the sanitizer`() {
        val html = render("""<input type="text" value="x">""")
        assertFalse(html, html.contains("<input"))
    }

    @Test
    fun `raw checkbox input is kept but forced disabled and non-interactive`() {
        val html = render("""<input type="checkbox" checked onclick="alert(1)">""")
        assertTrue(html, html.contains("<input"))
        assertTrue(html, html.contains("checkbox"))
        assertTrue(html, html.contains("disabled"))
        assertFalse(html, html.contains("onclick"))
    }
}
