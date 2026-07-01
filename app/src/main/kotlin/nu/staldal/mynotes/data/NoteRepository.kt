package nu.staldal.mynotes.data

import android.util.Log
import nu.staldal.mynotes.data.api.DefaultApi
import nu.staldal.mynotes.data.api.Note
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.local.ChangeType
import nu.staldal.mynotes.data.local.ConflictEntity
import nu.staldal.mynotes.data.local.NoteEntity
import nu.staldal.mynotes.data.local.PendingChange
import nu.staldal.mynotes.data.local.PendingChangeStatus
import nu.staldal.mynotes.data.local.mergeContentFrom
import nu.staldal.mynotes.data.local.toCreateRequest
import nu.staldal.mynotes.data.local.toEntity
import nu.staldal.mynotes.data.local.toUpdateRequest
import nu.staldal.mynotes.util.NoteDateUtils
import nu.staldal.mynotes.util.SlugGenerator
import kotlinx.coroutines.flow.Flow
import retrofit2.Response

const val LOGTAG = "NoteRepository"

private const val LIST_PAGE_SIZE = 200

class NoteRepository(
    database: AppDatabase,
    private val artifactRepository: ArtifactRepository,
    private val apiProvider: () -> DefaultApi?,
    private val baseUrlProvider: () -> String,
) {
    private val noteDao = database.noteDao()
    private val pendingChangeDao = database.pendingChangeDao()
    private val conflictDao = database.conflictDao()

    // --- Read surface -------------------------------------------------

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(slug: String): Flow<NoteEntity?> = noteDao.observeBySlug(slug)

    suspend fun getNote(slug: String): NoteEntity? = noteDao.getBySlug(slug)

    fun getPendingChangeCount(): Flow<Int> = pendingChangeDao.getPendingCount()

    fun getConflictCount(): Flow<Int> = pendingChangeDao.getConflictCount()

    fun observeConflicts(): Flow<List<ConflictEntity>> = conflictDao.observeAll()

    /** Ensures the cached note has full content, fetching it from the server if only a summary was cached. */
    suspend fun ensureFullContent(slug: String) {
        val existing = noteDao.getBySlug(slug) ?: return
        if (existing.hasFullContent) return
        val api = apiProvider() ?: return
        val response = api.getNote(slug)
        if (response.isSuccessful) {
            val note = response.body()!!
            val version = etagVersion(response.headers()["Etag"]) ?: note.version
            noteDao.upsert(note.toEntity(hasFullContent = true).copy(version = version))
        }
    }

    suspend fun search(query: String, online: Boolean): List<NoteEntity> {
        if (query.isBlank()) return noteDao.getAllOnce()
        if (!online) return noteDao.searchLocal(query)

        val api = apiProvider() ?: return noteDao.searchLocal(query)
        val response = api.listNotes(q = query, limit = LIST_PAGE_SIZE, offset = 0)
        if (!response.isSuccessful) return noteDao.searchLocal(query)

        val body = response.body()!!
        return body.notes.map { summary ->
            val existing = noteDao.getBySlug(summary.slug)
            summary.toEntity().mergeContentFrom(existing)
        }
    }

    // --- Mutations (write local + queue) -------------------------------

    suspend fun createNote(title: String, content: String): String {
        val existingSlugs = noteDao.getSlugVersionIndex().map { it.slug }.toSet()
        var slug = SlugGenerator.slugify(title)
        var suffix = 2
        while (slug in existingSlugs) {
            slug = SlugGenerator.withSuffix(SlugGenerator.slugify(title), suffix)
            suffix++
        }

        val now = NoteDateUtils.nowRfc3339()
        noteDao.upsert(
            NoteEntity(
                slug = slug,
                title = title,
                content = content,
                excerpt = "",
                createdAt = now,
                updatedAt = now,
                version = 0,
                hasFullContent = true,
            )
        )
        pendingChangeDao.insert(PendingChange(slug = slug, changeType = ChangeType.CREATE, baseVersion = 0))
        return slug
    }

    suspend fun updateNote(slug: String, title: String?, content: String?) {
        val existing = noteDao.getBySlug(slug) ?: return
        val updated = existing.copy(
            title = title ?: existing.title,
            content = content ?: existing.content,
            updatedAt = NoteDateUtils.nowRfc3339(),
        )
        noteDao.upsert(updated)

        // If this note was never synced (version == 0), the pending CREATE already carries the latest state.
        if (existing.version != 0) {
            pendingChangeDao.deleteNonDeleteBySlug(slug)
            pendingChangeDao.insert(
                PendingChange(slug = slug, changeType = ChangeType.UPDATE, baseVersion = existing.version)
            )
        }
    }

    suspend fun deleteNote(slug: String) {
        val existing = noteDao.getBySlug(slug) ?: return
        if (existing.version == 0) {
            // Never synced — nothing to tell the server.
            pendingChangeDao.deleteBySlug(slug)
            noteDao.deleteBySlug(slug)
        } else {
            noteDao.markPendingDelete(slug)
            pendingChangeDao.deleteNonDeleteBySlug(slug)
            pendingChangeDao.insert(
                PendingChange(slug = slug, changeType = ChangeType.DELETE, baseVersion = existing.version)
            )
        }
    }

    // --- Sync -----------------------------------------------------------

    /**
     * Pages through GET /notes, diffing (slug, version) against the local cache. There is no
     * delta/"since" endpoint, so a full listing pass is the only way to detect remote changes
     * and deletes. Full content is fetched only for slugs that are new or version-changed.
     * Slugs with a pending local change are skipped entirely so an unsynced local edit is never
     * clobbered by a refresh.
     */
    suspend fun refreshNotes() {
        val api = apiProvider() ?: return
        val localIndex = noteDao.getSlugVersionIndex().associate { it.slug to it.version }
        val pendingSlugs = pendingChangeDao.getAllChanges().map { it.slug }.toSet()
        val seenSlugs = mutableSetOf<String>()
        val changedSlugs = mutableListOf<String>()

        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val response = api.listNotes(q = null, limit = LIST_PAGE_SIZE, offset = offset)
            if (!response.isSuccessful) throw Exception("Unable to list notes: ${response.code()}")
            val page = response.body()!!
            total = page.total

            for (summary in page.notes) {
                seenSlugs += summary.slug
                if (summary.slug in pendingSlugs) continue

                val localVersion = localIndex[summary.slug]
                if (localVersion == null || localVersion != summary.version) {
                    changedSlugs += summary.slug
                }
                val existing = noteDao.getBySlug(summary.slug)
                noteDao.upsert(summary.toEntity().mergeContentFrom(existing))
            }
            offset += LIST_PAGE_SIZE
        }

        for (slug in changedSlugs) {
            val noteResponse = api.getNote(slug)
            if (!noteResponse.isSuccessful) continue
            val note = noteResponse.body()!!
            val version = etagVersion(noteResponse.headers()["Etag"]) ?: note.version
            noteDao.upsert(note.toEntity(hasFullContent = true).copy(version = version))
        }

        val staleSlugs = localIndex.keys - seenSlugs - pendingSlugs
        staleSlugs.forEach { noteDao.deleteBySlug(it) }
    }

    /** Replays queued local mutations against the server. Returns notifications about renames/remote deletes for the UI. */
    suspend fun syncPendingChanges(): List<String> {
        val api = apiProvider() ?: return emptyList()
        val notifications = mutableListOf<String>()
        val changes = pendingChangeDao.getAllChanges().filter { it.status == PendingChangeStatus.PENDING }

        for (change in changes) {
            try {
                when (change.changeType) {
                    ChangeType.CREATE -> syncCreate(api, change, notifications)
                    ChangeType.UPDATE -> syncUpdate(api, change, notifications)
                    ChangeType.DELETE -> syncDelete(api, change)
                }
            } catch (e: Exception) {
                Log.w(LOGTAG, "Failed to sync change to backend: $e")
                throw e
            }
        }
        return notifications
    }

    private suspend fun syncCreate(api: DefaultApi, change: PendingChange, notifications: MutableList<String>) {
        val entity = noteDao.getBySlug(change.slug) ?: run { pendingChangeDao.delete(change); return }
        val rewrittenContent = artifactRepository.uploadPendingArtifactsAndRewrite(entity.content, baseUrlProvider())
        val entityToSend = entity.copy(content = rewrittenContent)
        val request = entityToSend.toCreateRequest()

        val response = api.createNote(request)
        when {
            response.isSuccessful -> {
                val created = response.body()!!
                noteDao.upsert(created.toEntity(hasFullContent = true))
                pendingChangeDao.delete(change)
            }
            response.code() == 409 -> {
                var suffix = 2
                var renamedSlug: String
                var retryResponse: Response<Note>
                do {
                    renamedSlug = SlugGenerator.withSuffix(entity.slug, suffix)
                    retryResponse = api.createNote(request.copy(slug = renamedSlug))
                    suffix++
                } while (retryResponse.code() == 409 && suffix < 10)

                if (retryResponse.isSuccessful) {
                    noteDao.deleteBySlug(entity.slug)
                    noteDao.upsert(retryResponse.body()!!.toEntity(hasFullContent = true))
                    pendingChangeDao.delete(change)
                    notifications += "Note renamed from \"${entity.slug}\" to \"$renamedSlug\" (slug was already taken)"
                } else {
                    throw Exception("Create retry failed after rename: ${retryResponse.code()}")
                }
            }
            else -> throw Exception("Create failed: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun syncUpdate(api: DefaultApi, change: PendingChange, notifications: MutableList<String>) {
        val entity = noteDao.getBySlug(change.slug) ?: run { pendingChangeDao.delete(change); return }
        val rewrittenContent = artifactRepository.uploadPendingArtifactsAndRewrite(entity.content, baseUrlProvider())
        val ifMatch = "\"${change.baseVersion}\""

        val response = api.updateNote(change.slug, entity.copy(content = rewrittenContent).toUpdateRequest(), ifMatch)
        when {
            response.isSuccessful -> {
                val version = etagVersion(response.headers()["Etag"]) ?: response.body()!!.version
                noteDao.upsert(response.body()!!.toEntity(hasFullContent = true).copy(version = version))
                pendingChangeDao.delete(change)
            }
            response.code() == 404 -> {
                noteDao.deleteBySlug(change.slug)
                pendingChangeDao.delete(change)
                notifications += "Note \"${change.slug}\" was deleted on the server; local edit discarded"
            }
            response.code() == 412 -> {
                val serverResponse = api.getNote(change.slug)
                if (!serverResponse.isSuccessful) throw Exception("Unable to fetch server note for conflict: ${serverResponse.code()}")
                val server = serverResponse.body()!!
                val serverVersion = etagVersion(serverResponse.headers()["Etag"]) ?: server.version
                conflictDao.upsert(
                    ConflictEntity(
                        slug = change.slug,
                        localTitle = entity.title,
                        localContent = rewrittenContent,
                        localBaseVersion = change.baseVersion,
                        serverTitle = server.title,
                        serverContent = server.content,
                        serverVersion = serverVersion,
                        serverUpdatedAt = server.updatedAt,
                    )
                )
                pendingChangeDao.update(change.copy(status = PendingChangeStatus.CONFLICT))
            }
            else -> throw Exception("Update failed: ${response.code()} ${response.message()}")
        }
    }

    private suspend fun syncDelete(api: DefaultApi, change: PendingChange) {
        val response = api.deleteNote(change.slug)
        if (response.isSuccessful || response.code() == 404) {
            noteDao.deleteBySlug(change.slug)
            pendingChangeDao.delete(change)
        } else {
            throw Exception("Delete failed: ${response.code()} ${response.message()}")
        }
    }

    suspend fun resolveConflict(slug: String, keepLocal: Boolean) {
        val conflict = conflictDao.getBySlug(slug) ?: return
        val conflictChange = pendingChangeDao.getAllChanges()
            .firstOrNull { it.slug == slug && it.status == PendingChangeStatus.CONFLICT }

        if (keepLocal) {
            val existing = noteDao.getBySlug(slug)
            if (existing != null) {
                noteDao.upsert(existing.copy(title = conflict.localTitle, content = conflict.localContent, version = conflict.serverVersion))
            }
            if (conflictChange != null) pendingChangeDao.delete(conflictChange)
            pendingChangeDao.insert(
                PendingChange(slug = slug, changeType = ChangeType.UPDATE, baseVersion = conflict.serverVersion)
            )
        } else {
            noteDao.upsert(
                NoteEntity(
                    slug = slug,
                    title = conflict.serverTitle,
                    content = conflict.serverContent,
                    excerpt = "",
                    createdAt = conflict.serverUpdatedAt,
                    updatedAt = conflict.serverUpdatedAt,
                    version = conflict.serverVersion,
                    hasFullContent = true,
                )
            )
            if (conflictChange != null) pendingChangeDao.delete(conflictChange)
        }
        conflictDao.deleteBySlug(slug)
    }

    private fun etagVersion(etag: String?): Int? =
        etag?.trim('"')?.toIntOrNull()
}
