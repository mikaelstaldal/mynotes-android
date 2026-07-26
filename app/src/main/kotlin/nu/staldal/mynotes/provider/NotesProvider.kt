package nu.staldal.mynotes.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.ResolvedImage
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.local.NoteEntity
import nu.staldal.mynotes.data.preferences.ServerConfig
import nu.staldal.mynotes.data.preferences.UserPreferences
import java.io.FileNotFoundException

/**
 * Read-only access to this device's notes for other apps — see [NotesContract] for the interface.
 *
 * It exists so a sibling app can show a note *without* becoming a second notes client: no second
 * copy of the database, no second set of credentials, no second sync. Because it reads the same
 * Room cache the app itself reads, a consumer inherits this app's offline support for free — a note
 * that has been synced here is readable there with no network at all. (MyCal uses this to show the
 * note linked to a calendar event, rendering it with the same render kit this app uses.)
 *
 * Read access is gated by [NotesContract.PERMISSION_READ_NOTES], declared `signature` in the
 * manifest: only an app signed with the same key is granted it, at install time.
 *
 * The surface is deliberately narrow — everything below is a read:
 *  - `notes` / `notes/<slug>`: note metadata and content, as a cursor.
 *  - `artifacts/<sha256>` / `local-artifacts/<id>`: the images note content embeds, as streams.
 *
 * Writes are refused. A consumer that wants to change a note sends the user here with `ACTION_VIEW`
 * on [NotesContract.noteUri] instead, so editing always happens in this app, under its own
 * conflict handling.
 */
class NotesProvider : ContentProvider() {

    private companion object {
        const val LOGTAG = "NotesProvider"

        const val MATCH_NOTES = 1
        const val MATCH_NOTE = 2
        const val MATCH_ARTIFACT = 3
        const val MATCH_LOCAL_ARTIFACT = 4
        const val MATCH_TAG = 5

        /** Rows returned when the caller does not ask for a projection. */
        val DEFAULT_PROJECTION = arrayOf(
            NotesContract.COLUMN_SLUG,
            NotesContract.COLUMN_TITLE,
            NotesContract.COLUMN_CONTENT,
            NotesContract.COLUMN_UPDATED_AT,
            NotesContract.COLUMN_HAS_FULL_CONTENT,
        )

        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(NotesContract.AUTHORITY, NotesContract.PATH_NOTES, MATCH_NOTES)
        addURI(NotesContract.AUTHORITY, "${NotesContract.PATH_NOTES}/*", MATCH_NOTE)
        addURI(NotesContract.AUTHORITY, "${NotesContract.PATH_TAGS}/*", MATCH_TAG)
        addURI(NotesContract.AUTHORITY, "${NotesContract.PATH_ARTIFACTS}/*", MATCH_ARTIFACT)
        addURI(NotesContract.AUTHORITY, "${NotesContract.PATH_LOCAL_ARTIFACTS}/*", MATCH_LOCAL_ARTIFACT)
    }

    private val appContext: Context
        get() = checkNotNull(context) { "provider used before onCreate" }.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    private val noteDao by lazy { database.noteDao() }

    /**
     * The server configuration, re-read per call rather than cached: the provider process outlives
     * individual calls and the user can reconfigure the app at any time.
     */
    private fun serverConfig(): ServerConfig =
        runBlocking { UserPreferences(appContext).serverConfig.first() }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Repositories wired exactly as the app's own view models wire them, so a read through the
     * provider behaves like a read in the app: cache first, server only when configured and online.
     */
    private fun artifactRepository(config: ServerConfig) = ArtifactRepository(
        appContext,
        database,
        isOnlineProvider = { isOnline() && !config.offlineMode },
    ) {
        RetrofitClient.getApiService(config.baseUrl, config.username, config.password)
    }

    private fun noteRepository(config: ServerConfig) = NoteRepository(
        database = database,
        artifactRepository = artifactRepository(config),
        apiProvider = { RetrofitClient.getApiService(config.baseUrl, config.username, config.password) },
        baseUrlProvider = { config.baseUrl },
    )

    /**
     * The image most recently resolved, so `getType` and `openFile` on the same URI — the normal
     * sequence for a consumer that needs both the bytes and their content type — cost one
     * resolution rather than two.
     */
    private var lastImage: Pair<String, ResolvedImage>? = null

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        MATCH_NOTES -> NotesContract.MIME_NOTE_DIR
        MATCH_NOTE -> NotesContract.MIME_NOTE_ITEM
        MATCH_TAG -> NotesContract.MIME_TAG_ITEM
        // Resolving may download the image when it is not cached yet. That is a read of note data,
        // and getType is permission-checked for callers of an app targeting Android 12 or later.
        MATCH_ARTIFACT, MATCH_LOCAL_ARTIFACT -> resolveImage(uri)?.contentType
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        // `selection` and `sortOrder` are deliberately unsupported: the query surface is the URI
        // and its parameters (see NotesContract), which keeps callers away from the schema.
        require(selection == null && sortOrder == null) {
            "NotesProvider takes its query from the URI; selection and sortOrder are not supported"
        }
        val columns = projection ?: DEFAULT_PROJECTION
        return when (matcher.match(uri)) {
            MATCH_NOTES -> cursorOf(columns, listNotes(uri), uri)
            MATCH_NOTE -> cursorOf(columns, listOfNotNull(loadNote(uri.lastPathSegment)), uri)
            else -> null
        }
    }

    /** The note listing, optionally narrowed to a title prefix for autocomplete. */
    private fun listNotes(uri: Uri): List<NoteEntity> {
        val limit = uri.getQueryParameter(NotesContract.PARAM_LIMIT)
            ?.toIntOrNull()?.coerceIn(1, MAX_LIMIT)
            ?: DEFAULT_LIMIT
        val prefix = uri.getQueryParameter(NotesContract.PARAM_TITLE_PREFIX)?.trim()
        return runBlocking {
            if (prefix.isNullOrEmpty()) {
                noteDao.getRecent(limit)
            } else {
                noteDao.searchByTitlePrefix(escapeLikeWildcards(prefix), limit)
            }
        }
    }

    /**
     * One note, with its content fetched first if only a summary was ever synced. Offline that
     * fetch is a no-op and the note comes back with `has_full_content = 0`, which is the honest
     * answer: the note exists but its body is not on this device.
     */
    private fun loadNote(slug: String?): NoteEntity? {
        if (slug.isNullOrEmpty()) return null
        return runBlocking {
            try {
                noteRepository(serverConfig()).ensureFullContent(slug)
            } catch (e: Exception) {
                Log.w(LOGTAG, "Could not fetch full content for $slug", e)
            }
            noteDao.getBySlug(slug)?.takeUnless { it.isPendingDelete }
        }
    }

    private fun cursorOf(columns: Array<out String>, notes: List<NoteEntity>, uri: Uri): Cursor {
        val cursor = MatrixCursor(columns.map { it }.toTypedArray(), notes.size)
        for (note in notes) {
            cursor.addRow(columns.map { column -> valueOf(note, column, uri) })
        }
        return cursor
    }

    private fun valueOf(note: NoteEntity, column: String, uri: Uri): Any? = when (column) {
        NotesContract.COLUMN_SLUG -> note.slug
        NotesContract.COLUMN_TITLE -> note.title
        NotesContract.COLUMN_CONTENT -> if (note.hasFullContent) note.content else ""
        NotesContract.COLUMN_UPDATED_AT -> note.updatedAt
        NotesContract.COLUMN_HAS_FULL_CONTENT -> if (note.hasFullContent) 1 else 0
        else -> throw IllegalArgumentException("Unknown column '$column' in $uri")
    }

    /**
     * Streams an image note content embeds. The bytes come from the local artifact cache, and only
     * from the server when this app is online and the artifact was never cached — the same rule the
     * app's own note view follows.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("NotesProvider is read-only: $uri")
        val image = resolveImage(uri) ?: throw FileNotFoundException("No such image: $uri")
        return openPipeHelper(uri, image.contentType, null, image.bytes) { output, _, _, _, bytes ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) }
            } catch (e: java.io.IOException) {
                // The reader went away mid-write; nothing to recover, and throwing here would only
                // crash the pipe thread.
                Log.w(LOGTAG, "Image stream closed early", e)
            }
        }
    }

    /** Resolves the image [uri] refers to, memoizing the last one (see [lastImage]). */
    @Synchronized
    private fun resolveImage(uri: Uri): ResolvedImage? {
        val ref = imageRefFor(uri) ?: return null
        lastImage?.let { (cachedRef, cachedImage) -> if (cachedRef == ref) return cachedImage }
        val image = runBlocking {
            try {
                artifactRepository(serverConfig()).resolveImage(ref)
            } catch (e: Exception) {
                Log.w(LOGTAG, "Could not resolve $ref", e)
                null
            }
        } ?: return null
        lastImage = ref to image
        return image
    }

    /** The reference [ArtifactRepository.resolveImage] understands for an image URI. */
    private fun imageRefFor(uri: Uri): String? {
        val id = uri.lastPathSegment ?: return null
        return when (matcher.match(uri)) {
            MATCH_ARTIFACT -> "/api/v1/artifacts/$id"
            MATCH_LOCAL_ARTIFACT -> "local-artifact://$id"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("NotesProvider is read-only; edit notes in MyNotes")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("NotesProvider is read-only; edit notes in MyNotes")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("NotesProvider is read-only; edit notes in MyNotes")
}

/**
 * Escapes LIKE's wildcards so a search string is matched literally. Pairs with the `ESCAPE '\'`
 * clause in [nu.staldal.mynotes.data.local.NoteDao.searchByTitlePrefix]; without it, a user typing
 * `%` would match every note.
 */
internal fun escapeLikeWildcards(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
