package nu.staldal.mynotes.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure piece of [NotesProvider]: the escaping that keeps a caller's search string from
 * being read as a LIKE pattern. The rest of the provider is Android plumbing over the same DAOs the
 * app uses, and is covered by their tests.
 */
class NotesProviderTest {

    @Test
    fun `an ordinary prefix is unchanged`() {
        assertEquals("meeting", escapeLikeWildcards("meeting"))
        assertEquals("Q3 plan", escapeLikeWildcards("Q3 plan"))
    }

    @Test
    fun `like wildcards are escaped so they match literally`() {
        // Unescaped, a bare "%" would match every note.
        assertEquals("\\%", escapeLikeWildcards("%"))
        assertEquals("100\\% done", escapeLikeWildcards("100% done"))
        assertEquals("a\\_b", escapeLikeWildcards("a_b"))
    }

    @Test
    fun `the escape character itself is escaped first`() {
        assertEquals("\\\\", escapeLikeWildcards("\\"))
        // Otherwise this would come out as \% — an escaped wildcard rather than a literal backslash
        // followed by a wildcard.
        assertEquals("\\\\\\%", escapeLikeWildcards("\\%"))
    }
}
