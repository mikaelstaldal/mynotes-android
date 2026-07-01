package nu.staldal.mynotes.data.local

import androidx.room.*

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM artifacts WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): ArtifactEntity?

    @Upsert
    suspend fun upsert(artifact: ArtifactEntity)
}
