package nu.staldal.mynotes.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the `:shortcode:` emoji transform end-to-end through [NoteHtmlRenderer], mirroring the
 * web client's `emoji` inline rule (mynotes/web/ts/util/markdown.ts) and the "Emoji shortcodes"
 * section of mynotes/markdown-spec.md.
 */
class EmojiShortcodesTest {
    private fun render(markdown: String): String = NoteHtmlRenderer.renderToSanitizedHtml(markdown)

    /**
     * Asserts [shortcode] renders to [emoji]: the literal shortcode is gone and the emoji's first
     * code point appears. The OWASP sanitizer re-encodes non-ASCII base code points as numeric
     * character references (`🚀` -> `&#x1f680;`) while leaving some combining marks raw, so accept
     * either the raw char or the numeric-reference form.
     */
    private fun assertEmoji(shortcode: String, emoji: String) {
        val html = render(shortcode)
        assertFalse(html, html.contains(shortcode))
        val cp = emoji.codePointAt(0)
        val entity = "&#x%x;".format(cp)
        val char = String(Character.toChars(cp))
        assertTrue("$html (expected $char / $entity)", html.contains(char) || html.contains(entity))
    }

    @Test
    fun `known shortcode renders the unicode emoji`() {
        assertEmoji(":rocket:", "🚀") // 🚀
    }

    @Test
    fun `plus and underscore shortcodes are recognized`() {
        assertEmoji(":+1:", EmojiShortcodes.lookup("+1")!!)
        assertEmoji(":sweat_smile:", "😅") // 😅
    }

    @Test
    fun `unknown shortcode is left literal`() {
        assertTrue(render(":frobnicate:").contains(":frobnicate:"))
    }

    @Test
    fun `ordinary colon usage is untouched`() {
        assertTrue(render("meet at 12:30").contains("12:30"))
        assertTrue(render("a:b").contains("a:b"))
    }

    @Test
    fun `shortcode inside code span stays literal`() {
        val html = render("`:rocket:`")
        assertTrue(html, html.contains(":rocket:"))
        assertFalse(html, html.contains("🚀"))
    }
}
