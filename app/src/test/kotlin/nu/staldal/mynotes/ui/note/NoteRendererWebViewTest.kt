package nu.staldal.mynotes.ui.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pure pieces of the render-kit WebView host: the Markdown rewrite that lets a
 * locally-attached image survive the renderer's URL sanitization, and the request allow-list that
 * decides which requests the WebView is allowed to make.
 *
 * The Markdown dialect itself is not tested here — it is no longer implemented in this app. Its
 * tests live with the implementation, in the mynotes repo (web/ts/markdown.test.mjs).
 */
class NoteRendererWebViewTest {

    // --- local-artifact rewrite ---------------------------------------------

    @Test
    fun `local artifact reference becomes a root-relative path`() {
        val id = "3f2a1b4c-0d5e-4a6b-8c9d-0e1f2a3b4c5d"
        assertEquals(
            "![image](/local-artifact/$id)",
            rewriteLocalArtifactRefs("![image](local-artifact://$id)"),
        )
    }

    @Test
    fun `every local artifact reference is rewritten`() {
        val rewritten = rewriteLocalArtifactRefs(
            "![a](local-artifact://aaa) and ![b](local-artifact://bbb)"
        )
        assertEquals("![a](/local-artifact/aaa) and ![b](/local-artifact/bbb)", rewritten)
    }

    @Test
    fun `markdown without local artifacts is untouched`() {
        val md = "# Title\n\n![remote](/api/v1/artifacts/${"a".repeat(64)})\n"
        assertEquals(md, rewriteLocalArtifactRefs(md))
    }

    // --- request allow-list --------------------------------------------------

    @Test
    fun `rewritten local artifact path resolves back to its reference`() {
        assertEquals("local-artifact://abc-123", artifactRefFor("/local-artifact/abc-123"))
    }

    @Test
    fun `root-relative artifact url resolves`() {
        val sha = "0".repeat(64)
        assertEquals("/api/v1/artifacts/$sha", artifactRefFor("/api/v1/artifacts/$sha"))
    }

    @Test
    fun `artifact url under a server base path resolves`() {
        // uploadPendingArtifactsAndRewrite stores an absolute URL, whose path may carry a prefix
        // when the server is deployed under a subpath.
        val sha = "a1b2c3d4e5f6".repeat(5) + "abcd"
        assertEquals(sha.length, 64)
        assertEquals("/api/v1/artifacts/$sha", artifactRefFor("/mynotes/api/v1/artifacts/$sha"))
    }

    @Test
    fun `a non-image path is not resolvable`() {
        assertNull(artifactRefFor("/notes/some-note"))
        assertNull(artifactRefFor("/"))
        assertNull(artifactRefFor("/evil.example/tracker.png"))
    }

    @Test
    fun `an icon request is not resolvable`() {
        // Known icons are inlined as <svg> by the renderer; an escaping request is for an icon the
        // vendored kit does not have, and is blocked rather than fetched.
        assertNull(artifactRefFor("/api/v1/icons/lucide/anchor"))
    }

    @Test
    fun `a malformed artifact sha is not resolvable`() {
        assertNull(artifactRefFor("/api/v1/artifacts/tooshort"))
        assertNull(artifactRefFor("/api/v1/artifacts/${"A".repeat(64)}")) // hex is lowercase
        assertNull(artifactRefFor("/api/v1/artifacts/${"0".repeat(64)}/extra"))
    }
}
