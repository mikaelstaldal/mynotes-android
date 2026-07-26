package nu.staldal.mynotes.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class SlugVersion(val slug: String, val version: Int)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isPendingDelete = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isPendingDelete = 0 ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE slug = :slug")
    suspend fun getBySlug(slug: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE slug = :slug")
    fun observeBySlug(slug: String): Flow<NoteEntity?>

    @Query(
        "SELECT * FROM notes WHERE isPendingDelete = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY updatedAt DESC"
    )
    suspend fun searchLocal(query: String): List<NoteEntity>

    /**
     * Autocomplete matching for the content provider: titles starting with [prefix]. The caller
     * escapes LIKE's own wildcards with a backslash, so a title containing `%` or `_` is matched
     * literally rather than as a pattern.
     */
    @Query(
        "SELECT * FROM notes WHERE isPendingDelete = 0 AND title LIKE :prefix || '%' ESCAPE '\\' ORDER BY title LIMIT :limit"
    )
    suspend fun searchByTitlePrefix(prefix: String, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE isPendingDelete = 0 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE slug = :slug")
    suspend fun deleteBySlug(slug: String)

    @Query("UPDATE notes SET isPendingDelete = 1 WHERE slug = :slug")
    suspend fun markPendingDelete(slug: String)

    @Query("SELECT slug, version FROM notes WHERE isPendingDelete = 0")
    suspend fun getSlugVersionIndex(): List<SlugVersion>
}
