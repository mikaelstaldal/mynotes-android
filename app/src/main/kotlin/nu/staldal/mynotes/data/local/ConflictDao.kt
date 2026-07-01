package nu.staldal.mynotes.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts WHERE slug = :slug")
    suspend fun getBySlug(slug: String): ConflictEntity?

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Query("DELETE FROM conflicts WHERE slug = :slug")
    suspend fun deleteBySlug(slug: String)
}
