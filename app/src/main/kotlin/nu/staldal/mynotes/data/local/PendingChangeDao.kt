package nu.staldal.mynotes.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingChangeDao {
    @Query("SELECT * FROM pending_changes ORDER BY timestamp ASC")
    suspend fun getAllChanges(): List<PendingChange>

    @Query("SELECT COUNT(*) FROM pending_changes WHERE status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_changes WHERE status = 'CONFLICT'")
    fun getConflictCount(): Flow<Int>

    @Insert
    suspend fun insert(change: PendingChange)

    @Update
    suspend fun update(change: PendingChange)

    @Delete
    suspend fun delete(change: PendingChange)

    @Query("DELETE FROM pending_changes WHERE slug = :slug")
    suspend fun deleteBySlug(slug: String)

    @Query("DELETE FROM pending_changes WHERE slug = :slug AND changeType != 'DELETE'")
    suspend fun deleteNonDeleteBySlug(slug: String)
}
