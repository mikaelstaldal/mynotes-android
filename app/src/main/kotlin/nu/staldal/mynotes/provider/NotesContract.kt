package nu.staldal.mynotes.provider

import android.net.Uri

/**
 * The contract of [NotesProvider] — the read-only interface this app offers to other apps on the
 * device, so a sibling app (MyCal) can show a note without a second copy of the notes or a second
 * connection to the server.
 *
 * **This is a published interface.** A consumer necessarily hard-codes these strings; changing any
 * of them breaks an installed consumer that this repo cannot see. Add, don't rename.
 *
 * Access is gated by [PERMISSION_READ_NOTES], declared `signature`: only an app signed with the
 * same key as this one is granted it. Notes are personal data, and this is the whole of the
 * gatekeeping — there is no per-note authorization.
 */
object NotesContract {

    /** Authority of the provider. Matches the manifest declaration. */
    const val AUTHORITY = "nu.staldal.mynotes.notes"

    /** Package of this app, for a consumer building an explicit intent into it. */
    const val PACKAGE = "nu.staldal.mynotes"

    /** Signature-level permission guarding every read below. */
    const val PERMISSION_READ_NOTES = "nu.staldal.mynotes.permission.READ_NOTES"

    private val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")

    // --- Paths ---------------------------------------------------------------

    const val PATH_NOTES = "notes"
    const val PATH_TAGS = "tags"
    const val PATH_ARTIFACTS = "artifacts"
    const val PATH_LOCAL_ARTIFACTS = "local-artifacts"

    // --- Query parameters (the provider ignores `selection` and `sortOrder`) --

    /** Restricts a note listing to titles starting with this value, case-insensitively. */
    const val PARAM_TITLE_PREFIX = "titlePrefix"

    /** Caps the number of rows returned by a note listing. */
    const val PARAM_LIMIT = "limit"

    // --- Cursor columns ------------------------------------------------------

    const val COLUMN_SLUG = "slug"
    const val COLUMN_TITLE = "title"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_UPDATED_AT = "updated_at"

    /**
     * 1 when [COLUMN_CONTENT] is the note's real content, 0 when only a summary has been
     * synced — the note exists but its body has not been downloaded and cannot be while offline.
     */
    const val COLUMN_HAS_FULL_CONTENT = "has_full_content"

    // --- MIME types ----------------------------------------------------------

    const val MIME_NOTE_DIR = "vnd.android.cursor.dir/vnd.nu.staldal.mynotes.note"
    const val MIME_NOTE_ITEM = "vnd.android.cursor.item/vnd.nu.staldal.mynotes.note"
    const val MIME_TAG_ITEM = "vnd.android.cursor.item/vnd.nu.staldal.mynotes.tag"

    // --- URIs ----------------------------------------------------------------

    /** All notes, most recently updated first. Accepts [PARAM_TITLE_PREFIX] and [PARAM_LIMIT]. */
    fun notesUri(): Uri = BASE_URI.buildUpon().appendPath(PATH_NOTES).build()

    /** A single note, including its content. */
    fun noteUri(slug: String): Uri =
        BASE_URI.buildUpon().appendPath(PATH_NOTES).appendPath(slug).build()

    /**
     * A tag. Not queryable — it exists so a consumer can send the user here with `ACTION_VIEW`,
     * the target of a `#tag` link in a rendered note.
     */
    fun tagUri(slug: String): Uri =
        BASE_URI.buildUpon().appendPath(PATH_TAGS).appendPath(slug).build()

    /**
     * An image the server stores content-addressed, as referenced by `/api/v1/artifacts/<sha256>`
     * in note content. Read it with `ContentResolver.openInputStream`; its content type comes from
     * `ContentResolver.getType` on the same URI.
     */
    fun artifactUri(sha256: String): Uri =
        BASE_URI.buildUpon().appendPath(PATH_ARTIFACTS).appendPath(sha256).build()

    /**
     * An image attached on this device and not yet uploaded, as referenced by
     * `local-artifact://<id>` in note content.
     */
    fun localArtifactUri(localId: String): Uri =
        BASE_URI.buildUpon().appendPath(PATH_LOCAL_ARTIFACTS).appendPath(localId).build()
}
