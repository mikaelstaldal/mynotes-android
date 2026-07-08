package nu.staldal.mynotes.ui.note

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [wikiLinkBlankLinePrefix], the helper that decides whether a wikilink inserted at
 * the cursor needs a leading blank line to escape a preceding raw HTML/SVG/MathML block.
 */
class WikiLinkBlankLinePrefixTest {
    @Test
    fun `no prefix at start of empty content`() {
        assertEquals("", wikiLinkBlankLinePrefix(""))
    }

    @Test
    fun `no prefix in the middle of a paragraph`() {
        assertEquals("", wikiLinkBlankLinePrefix("See the note "))
    }

    @Test
    fun `full blank line when cursor is on the same line as a closing tag`() {
        assertEquals("\n\n", wikiLinkBlankLinePrefix("<svg>...</svg>"))
    }

    @Test
    fun `one newline when cursor is on the line after a closing tag`() {
        assertEquals("\n", wikiLinkBlankLinePrefix("<svg>...</svg>\n"))
    }

    @Test
    fun `no prefix when a blank line already separates the tag`() {
        assertEquals("", wikiLinkBlankLinePrefix("<svg>...</svg>\n\n"))
    }

    @Test
    fun `trailing spaces after a tag still count as the same line`() {
        assertEquals("\n\n", wikiLinkBlankLinePrefix("</math>   "))
    }

    @Test
    fun `self-closing tag with attributes is detected`() {
        assertEquals("\n\n", wikiLinkBlankLinePrefix("""<rect x="1" y="2"/>"""))
    }

    @Test
    fun `multi-line MathML block on the next line`() {
        assertEquals("\n", wikiLinkBlankLinePrefix("<math>\n  <mi>x</mi>\n</math>\n"))
    }

    @Test
    fun `url autolink is not mistaken for a tag`() {
        assertEquals("", wikiLinkBlankLinePrefix("Visit <https://example.com>"))
    }
}
