package nu.staldal.mynotes.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the inline-icon (`[!name]`), box/foldable-blockquote (`>*`/`>-`/`>+`), callout and
 * alias-tinted-paragraph transforms end-to-end through [NoteHtmlRenderer], mirroring the web client
 * (mynotes/web/ts/util/markdown.ts) and mynotes/markdown-spec.md
 * ("Inline icons, boxes, and callouts").
 */
class IconAndCalloutTest {
    private fun render(markdown: String): String = NoteHtmlRenderer.renderToSanitizedHtml(markdown)

    // --- Inline icons -------------------------------------------------------

    @Test
    fun `bare lucide icon renders inline svg`() {
        val html = render("go [!rocket] now")
        assertTrue(html, html.contains("<svg"))
        assertTrue(html, html.contains("lucide-rocket"))
    }

    @Test
    fun `alias icon resolves to its lucide icon`() {
        assertTrue(render("[!warning]").contains("lucide-triangle-alert"))
    }

    @Test
    fun `explicit lucide form bypasses the alias table`() {
        // [!summary] is the gray alias (clipboard-list); [!lucide-summary] is the literal icon.
        assertTrue(render("[!summary]").contains("lucide-clipboard-list"))
        assertTrue(render("[!lucide-summary]").contains("lucide-summary"))
    }

    @Test
    fun `unknown icon name is left literal`() {
        val html = render("[!frobnicate]")
        assertTrue(html, html.contains("[!frobnicate]"))
        assertFalse(html, html.contains("<svg"))
    }

    // --- Callouts -----------------------------------------------------------

    @Test
    fun `alias blockquote becomes a callout with title and body`() {
        val html = render("> [!warning] Heads up\n> Body text.")
        assertTrue(html, html.contains("class=\"callout callout-warning callout-color-amber\""))
        assertTrue(html, html.contains("callout-title"))
        assertTrue(html, html.contains("Heads up"))
        assertTrue(html, html.contains("Body text."))
        assertTrue(html, html.contains("lucide-triangle-alert"))
    }

    @Test
    fun `callout title defaults to capitalized alias`() {
        val html = render("> [!warning]\n> Body")
        assertTrue(html, html.contains(">Warning<"))
    }

    @Test
    fun `foldable marker renders a details summary`() {
        val html = render(">- [!tip] Click to expand\n> Collapsed body")
        assertTrue(html, html.contains("<details"))
        assertTrue(html, html.contains("callout-foldable"))
        assertTrue(html, html.contains("<summary"))
        assertFalse(html, html.contains("open"))
    }

    @Test
    fun `expanded foldable marker adds the open attribute`() {
        val html = render(">+ [!tip] Expanded\n> Body")
        assertTrue(html, html.contains("<details"))
        assertTrue(html, html.contains("open"))
    }

    @Test
    fun `obsidian fold marker after alias makes it foldable`() {
        val html = render("> [!tip]- Click\n> Body")
        assertTrue(html, html.contains("<details"))
        assertTrue(html, html.contains("callout-foldable"))
    }

    @Test
    fun `static box marker without alias uses the default gray callout`() {
        val html = render(">* Just a title\n> and a body")
        assertTrue(html, html.contains("class=\"callout\""))
        assertTrue(html, html.contains("callout-title"))
        assertTrue(html, html.contains("Just a title"))
        assertFalse(html, html.contains("<details"))
    }

    @Test
    fun `unrecognized alias is an ordinary blockquote`() {
        val html = render("> [!frobnicate] not a box")
        assertFalse(html, html.contains("class=\"callout"))
        assertTrue(html, html.contains("<blockquote"))
        assertTrue(html, html.contains("[!frobnicate]"))
    }

    @Test
    fun `explicit lucide icon in blockquote is not a callout`() {
        // [!lucide-flame] carries no alias, so it stays an ordinary blockquote.
        val html = render("> [!lucide-flame] just an icon")
        assertFalse(html, html.contains("class=\"callout"))
        assertTrue(html, html.contains("<blockquote"))
        assertTrue(html, html.contains("lucide-flame"))
    }

    @Test
    fun `plain blockquote is untouched`() {
        val html = render("> just a quote")
        assertFalse(html, html.contains("callout"))
        assertTrue(html, html.contains("<blockquote"))
    }

    // --- Alias-tinted paragraphs -------------------------------------------

    @Test
    fun `alias at paragraph start tints the paragraph`() {
        val html = render("[!info] a tinted note")
        assertTrue(html, html.contains("callout-para callout-color-blue"))
        assertTrue(html, html.contains("lucide-info"))
        assertFalse(html, html.contains("<blockquote"))
    }

    @Test
    fun `non-alias icon does not tint the paragraph`() {
        val html = render("[!rocket] not tinted")
        assertFalse(html, html.contains("callout-para"))
    }
}
