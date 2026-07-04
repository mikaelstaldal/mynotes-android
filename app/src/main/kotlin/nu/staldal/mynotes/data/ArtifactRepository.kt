package nu.staldal.mynotes.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import nu.staldal.mynotes.data.api.DefaultApi
import nu.staldal.mynotes.data.api.FileRequestBodyConverterFactory
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.local.ArtifactEntity
import java.io.File
import java.util.UUID

/** An image reference resolved to its raw bytes and content type. */
data class ResolvedImage(val bytes: ByteArray, val contentType: String)

/** Matches an `<img src="...">` attribute, capturing the URL for resolution. */
private val IMG_SRC_ATTR = Regex("""(<img\b[^>]*?\ssrc=")([^"]*)(")""")

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

    /** Deletes an artifact's cached file (if present) and its row. */
    private suspend fun deleteArtifact(artifact: ArtifactEntity) {
        File(artifact.localFilePath).delete()
        artifactDao.delete(artifact)
    }

    /**
     * Removes cached artifact files/rows that are no longer referenced by [referencedLocalIds] and
     * whose upload has completed. Artifacts still pending upload are retained so an offline-attached
     * image is never lost before it reaches the server. Intended to run after a sync pass.
     */
    suspend fun deleteOrphanedArtifacts(referencedLocalIds: Set<String>) {
        for (artifact in artifactDao.getAll()) {
            if (artifact.uploadPending) continue
            if (artifact.localId in referencedLocalIds) continue
            deleteArtifact(artifact)
        }
    }

    /** Deletes every cached artifact file/row owned by [ownerSlug]. Called when the owning note is deleted. */
    suspend fun deleteArtifactsForNote(ownerSlug: String) {
        for (artifact in artifactDao.getByOwnerSlug(ownerSlug)) {
            deleteArtifact(artifact)
        }
    }

    fun localFileFor(localId: String): File? {
        val path = File(context.filesDir, "artifacts/$localId")
        return path.takeIf { it.exists() }
    }

    /**
     * Resolves an image reference found in note content (local placeholder or remote artifact
     * URL) to its raw bytes and content type.
     */
    suspend fun resolveImage(ref: String): ResolvedImage? {
        LOCAL_ARTIFACT_REF.find(ref)?.let { match ->
            val localId = match.groupValues[1]
            val artifact = artifactDao.getByLocalId(localId) ?: return null
            val bytes = localFileFor(localId)?.readBytes() ?: return null
            return ResolvedImage(bytes, artifact.contentType)
        }
        val sha256 = Regex("/api/v1/artifacts/([0-9a-f]{64})").find(ref)?.groupValues?.get(1) ?: return null
        val api = apiProvider() ?: return null
        val response = api.getArtifact(sha256)
        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
        val contentType = body.contentType()?.toString() ?: return null
        if (contentType !in ALLOWED_ARTIFACT_CONTENT_TYPES) return null
        return ResolvedImage(body.bytes(), contentType)
    }

    /**
     * Rewrites every `<img src="...">` in sanitized note HTML (local-artifact:// placeholder or
     * remote artifact URL) to a `data:` URI carrying the resolved bytes, so the WebView never
     * needs network access or app credentials to display an image. An image that fails to
     * resolve (e.g. offline and not yet cached) is left as-is and renders as a broken image.
     */
    suspend fun rewriteImageSrcToDataUris(html: String): String {
        val matches = IMG_SRC_ATTR.findAll(html).toList()
        if (matches.isEmpty()) return html
        val sb = StringBuilder()
        var lastEnd = 0
        for (match in matches) {
            val (prefix, src, suffix) = match.destructured
            sb.append(html, lastEnd, match.range.first)
            val resolved = resolveImage(src)
            if (resolved != null) {
                val base64 = Base64.encodeToString(resolved.bytes, Base64.NO_WRAP)
                sb.append(prefix).append("data:${resolved.contentType};base64,$base64").append(suffix)
            } else {
                sb.append(match.value)
            }
            lastEnd = match.range.last + 1
        }
        sb.append(html, lastEnd, html.length)
        return sb.toString()
    }
}
