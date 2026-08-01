package nu.staldal.mynotes.ui.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure part of the HTML export: the document the rendered note fragment is wrapped in.
 *
 * The rendering itself is the vendored render kit's, driven in a WebView, and is tested where it is
 * implemented (the mynotes repo); the fragment is opaque here.
 */
class NoteHtmlExportTest {

    /** Stands in for the vendored kit's note.css — enough of it to exercise the print reset. */
    private val noteCss = """
        :root {
          color-scheme: light;
          --bg: #ffffff;
          --fg: #1f2937;
        }
        :root[data-theme="dark"] {
          color-scheme: dark;
          --bg: #111827;
          --fg: #f3f4f6;
        }
        .note-content p { margin: 0.75em 0; }
    """.trimIndent()

    private fun wrap(title: String = "Note", fragment: String = "<p>hi</p>", dark: Boolean = false) =
        wrapNoteDocument(title, fragment, dark, noteCss)

    @Test
    fun `the document is standalone`() {
        val html = wrap()
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
        // The kit's stylesheet and the page frame travel inside the document, not as a link.
        assertFalse(html.contains("<link"))
        assertTrue(html.contains(".note-content p { margin: 0.75em 0; }"))
        assertTrue(html.contains("max-width: 65ch;"))
        // The fragment is placed in the container note.css styles.
        assertTrue(html.contains("<div class=\"note-content\">\n<p>hi</p>"))
    }

    @Test
    fun `the title is escaped`() {
        val html = wrap(title = """Tom & Jerry <script> "quoted"""")
        assertTrue(html.contains("<title>Tom &amp; Jerry &lt;script&gt; &quot;quoted&quot;</title>"))
    }

    @Test
    fun `a light export carries no theme attribute`() {
        val html = wrap(dark = false)
        assertTrue(html.contains("<html lang=\"en\">"))
        assertFalse(html.contains("data-theme=\"dark\">"))
        // Nothing to reset when printing a light document.
        assertFalse(html.contains("@media print {\n  :root"))
    }

    @Test
    fun `a dark export bakes in the theme and prints light`() {
        val html = wrap(dark = true)
        assertTrue(html.contains("<html lang=\"en\" data-theme=\"dark\">"))
        // The print reset restates note.css's own light values, lifted from its :root block.
        val printReset = html.substringAfter("@media print {\n  :root[data-theme=\"dark\"] {")
            .substringBefore("}")
        assertTrue(printReset.contains("--bg: #ffffff;"))
        assertTrue(printReset.contains("--fg: #1f2937;"))
        assertFalse(printReset.contains("#111827"))
    }

    @Test
    fun `escapeHtml leaves ordinary text alone`() {
        assertEquals("plain text", escapeHtml("plain text"))
    }
}
