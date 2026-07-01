package nu.staldal.mynotes.data

import android.content.Context
import android.net.Uri
import nu.staldal.mynotes.data.api.DefaultApi
import nu.staldal.mynotes.data.api.FileRequestBodyConverterFactory
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.local.ArtifactEntity
import java.io.File
import java.util.UUID

val ALLOWED_ARTIFACT_CONTENT_TYPES = setOf(
    "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "application/mathml+xml"
)

/** Regex matching the local placeholder inserted into note content for an offline-attached image. */
val LOCAL_ARTIFACT_REF = Regex("local-artifact://([\\w-]+)")

class ArtifactRepository(
    private val context: Context,
    database: AppDatabase,
    private val apiProvider: () -> DefaultApi?,
) {
    private val artifactDao = database.artifactDao()

    /** Copies [uri]'s bytes into the local artifact cache and returns the placeholder localId. */
    suspend fun attachLocalImage(ownerSlug: String, uri: Uri, contentType: String): String {
        require(contentType in ALLOWED_ARTIFACT_CONTENT_TYPES) { "Unsupported content type: $contentType" }

        val localId = UUID.randomUUID().toString()
        val artifactsDir = File(context.filesDir, "artifacts").apply { mkdirs() }
        val destFile = File(artifactsDir, localId)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open $uri")

        artifactDao.upsert(
            ArtifactEntity(
                localId = localId,
                localFilePath = destFile.absolutePath,
                contentType = contentType,
                sha256 = null,
                uploadPending = true,
                ownerSlug = ownerSlug,
            )
        )
        return localId
    }

    /** Uploads the artifact if it hasn't been uploaded yet. Re-upload of identical bytes is idempotent server-side. */
    suspend fun uploadIfNeeded(artifact: ArtifactEntity): ArtifactEntity {
        if (!artifact.uploadPending && artifact.sha256 != null) return artifact
        val api = apiProvider() ?: throw IllegalStateException("Not configured / offline")

        val file = File(artifact.localFilePath)

        FileRequestBodyConverterFactory.setPendingContentType(artifact.contentType)
        val response = try {
            api.createArtifact(file)
        } finally {
            FileRequestBodyConverterFactory.setPendingContentType(null)
        }
        if (!response.isSuccessful) {
            throw Exception("Unable to upload artifact: ${response.code()} ${response.message()}")
        }
        val created = response.body()!!
        val updated = artifact.copy(sha256 = created.sha256, uploadPending = false)
        artifactDao.upsert(updated)
        return updated
    }

    /**
     * Scans [content] for local-artifact:// placeholders, uploads each referenced artifact if
     * needed, and returns content with placeholders rewritten to real artifact URLs. Must be
     * called before the owning note's CREATE/UPDATE is sent to the server, so content never
     * references an artifact the server doesn't have yet.
     */
    suspend fun uploadPendingArtifactsAndRewrite(content: String, baseUrl: String): String {
        var rewritten = content
        val localIds = LOCAL_ARTIFACT_REF.findAll(content).map { it.groupValues[1] }.toSet()
        for (localId in localIds) {
            val artifact = artifactDao.getByLocalId(localId) ?: continue
            val uploaded = uploadIfNeeded(artifact)
            val normalizedBase = baseUrl.trimEnd('/')
            rewritten = rewritten.replace(
                "local-artifact://$localId",
                "$normalizedBase/api/v1/artifacts/${uploaded.sha256}"
            )
        }
        return rewritten
    }

    fun localFileFor(localId: String): File? {
        val path = File(context.filesDir, "artifacts/$localId")
        return path.takeIf { it.exists() }
    }

    /**
     * Resolves an image reference found in note content (local placeholder or remote artifact
     * URL) to raw bytes.
     *
     * NOTE: assumes the generated `getArtifact` returns `Response<ResponseBody>` (raw binary,
     * multiple possible content types) — verify against the generated DefaultApi once built with
     * network access.
     */
    suspend fun resolveImageBytes(ref: String): ByteArray? {
        LOCAL_ARTIFACT_REF.find(ref)?.let { match ->
            return localFileFor(match.groupValues[1])?.readBytes()
        }
        val sha256 = Regex("/api/v1/artifacts/([0-9a-f]{64})").find(ref)?.groupValues?.get(1) ?: return null
        val api = apiProvider() ?: return null
        val response = api.getArtifact(sha256)
        if (!response.isSuccessful) return null
        return response.body()?.bytes()
    }

    /** Extracts all image reference URLs (Markdown `![alt](ref)` syntax) from note content. */
    fun extractImageRefs(content: String): List<String> =
        Regex("!\\[[^\\]]*]\\(([^)]+)\\)").findAll(content).map { it.groupValues[1] }.toList()
}
