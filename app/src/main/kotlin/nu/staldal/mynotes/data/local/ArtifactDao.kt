package nu.staldal.mynotes.data.local

import androidx.room.*

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM artifacts WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts")
    suspend fun getAll(): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE ownerSlug = :ownerSlug")
    suspend fun getByOwnerSlug(ownerSlug: String): List<ArtifactEntity>

    @Upsert
    suspend fun upsert(artifact: ArtifactEntity)

    @Delete
    suspend fun delete(artifact: ArtifactEntity)
}
