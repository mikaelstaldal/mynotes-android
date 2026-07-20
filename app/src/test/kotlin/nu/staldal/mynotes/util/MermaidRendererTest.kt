package nu.staldal.mynotes.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the ```mermaid pipeline: a fenced `mermaid` block must survive Markdown rendering and the
 * [NoteHtmlRenderer] sanitizer as `<code class="language-mermaid">` so [MermaidRenderer.containsDiagram]
 * can detect it and drive the bundled engine, while ordinary notes stay JavaScript-free.
 */
class MermaidRendererTest {
    private val diagramNote = NoteHtmlRenderer.renderToSanitizedHtml(
        "# Flow\n\n```mermaid\ngraph TD;\n    A-->B;\n```\n",
    )

    @Test
    fun `fenced mermaid block keeps its language class through sanitization`() {
        assertTrue(diagramNote, diagramNote.contains("class=\"language-mermaid\""))
        // The diagram source is preserved as (escaped) text, not stripped.
        assertTrue(diagramNote, diagramNote.contains("graph TD"))
    }

    @Test
    fun `containsDiagram detects a mermaid note and ignores a plain one`() {
        assertTrue(MermaidRenderer.containsDiagram(diagramNote))
        val plain = NoteHtmlRenderer.renderToSanitizedHtml("Just some **prose**, no diagram.")
        assertFalse(plain, MermaidRenderer.containsDiagram(plain))
    }

    @Test
    fun `scriptTags bundles the engine and a theme-selecting driver`() {
        val dark = MermaidRenderer.scriptTags(dark = true)
        // The vendored global build installs window.mermaid; the driver renders and initializes it.
        assertTrue(dark.contains("globalThis[\"mermaid\"]"))
        assertTrue(dark.contains("mermaid.render("))
        assertTrue(dark.contains("securityLevel: 'strict'"))
        assertTrue(dark.contains("theme: 'dark'"))
        assertEquals(true, MermaidRenderer.scriptTags(dark = false).contains("theme: 'default'"))
    }
}
