package nu.staldal.mynotes.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<TagEntity>

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE slug = :slug")
    suspend fun deleteBySlug(slug: String)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    /** Replaces the full cached tag set with [tags], reflecting server-side deletions too. */
    @Transaction
    suspend fun replaceAll(tags: List<TagEntity>) {
        deleteAll()
        upsertAll(tags)
    }
}
