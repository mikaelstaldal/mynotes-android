package nu.staldal.mynotes.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises built-in Lucide icon inlining, both the [LucideIcons] helpers directly and end-to-end
 * through [NoteHtmlRenderer] (so the OWASP sanitizer's handling of the emitted `<svg>` is covered).
 * Mirrors the web client (mynotes/web/ts/util/markdown.ts) and server (mynotes/internal/icons).
 */
class LucideIconsTest {
    private fun render(markdown: String): String = NoteHtmlRenderer.renderToSanitizedHtml(markdown)

    @Test
    fun `icon image reference is inlined as an svg that survives sanitization`() {
        val html = render("![plane](/api/v1/icons/lucide/plane)")
        assertFalse(html, html.contains("<img"))
        assertTrue(html, html.contains("<svg"))
        assertTrue(html, html.contains("<path"))
        assertTrue(html, html.contains("lucide-plane"))
        // stroke=currentColor is what makes the icon follow the app's foreground/theme.
        assertTrue(html, html.contains("currentColor"))
        // viewbox must survive the (case-sensitive) sanitizer so the icon scales correctly.
        assertTrue(html, html.contains("viewbox=\"0 0 24 24\""))
    }

    @Test
    fun `multi-element icon keeps all its shapes as siblings`() {
        // alarm-clock-plus is a circle plus six paths. Self-closing SVG tags would nest the paths
        // inside the circle (only the circle renders); each shape must survive as a flat sibling.
        val svg = LucideIcons.renderIconSvg("alarm-clock-plus")!!
        assertEquals("all six paths kept: $svg", 6, svg.split("<path").size - 1)
        val html = render("![a](/api/v1/icons/lucide/alarm-clock-plus)")
        assertEquals("all six paths survive sanitization: $html", 6, html.split("<path").size - 1)
        assertTrue(html, html.contains("<circle"))
        // No nesting: a path must never sit inside another shape after sanitization.
        assertFalse(html, html.contains("<circle") && Regex("<circle[^>]*>\\s*<path").containsMatchIn(html))
    }

    @Test
    fun `absolute basepath-prefixed icon src is inlined`() {
        val html = render("![p](https://notes.example/base/api/v1/icons/lucide/plane)")
        assertFalse(html, html.contains("<img"))
        assertTrue(html, html.contains("lucide-plane"))
    }

    @Test
    fun `unknown icon name is left as an image`() {
        val html = render("![x](/api/v1/icons/lucide/definitely-not-an-icon)")
        assertFalse(html, html.contains("<svg"))
        assertTrue(html, html.contains("<img"))
    }

    @Test
    fun `ordinary image is not affected`() {
        val html = render("![photo](https://example.com/photo.png)")
        assertTrue(html, html.contains("<img"))
        assertFalse(html, html.contains("<svg"))
    }

    @Test
    fun `iconNameFromSrc handles the accepted src shapes and rejects others`() {
        assertEquals("plane", LucideIcons.iconNameFromSrc("/api/v1/icons/lucide/plane"))
        assertEquals("plane", LucideIcons.iconNameFromSrc("api/v1/icons/lucide/plane"))
        assertEquals("plane", LucideIcons.iconNameFromSrc("https://h/x/api/v1/icons/lucide/plane"))
        assertEquals("a-arrow-down", LucideIcons.iconNameFromSrc("/api/v1/icons/lucide/a-arrow-down?v=1"))
        assertNull(LucideIcons.iconNameFromSrc("https://example.com/photo.png"))
        assertNull(LucideIcons.iconNameFromSrc("/api/v1/icons/lucide/Bad_Name"))
    }

    @Test
    fun `renderIconSvg returns null for an unknown name`() {
        assertNull(LucideIcons.renderIconSvg("definitely-not-an-icon"))
    }
}
