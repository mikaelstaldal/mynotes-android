package nu.staldal.mynotes.data.api

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.io.File
import java.lang.reflect.Type

/**
 * Converts a `java.io.File` `@Body` parameter into a `RequestBody`.
 *
 * openapi-generator collapses `createArtifact`'s multi-content-type binary upload (image/png,
 * image/jpeg, ...) into a single `File` parameter with no way to specify the Content-Type per
 * call. [pendingContentType] lets the caller supply it out-of-band for the duration of the call.
 */
class FileRequestBodyConverterFactory : Converter.Factory() {
    companion object {
        private val pendingContentType = ThreadLocal<String?>()

        fun setPendingContentType(contentType: String?) {
            pendingContentType.set(contentType)
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<File, RequestBody>? {
        if (type != File::class.java) return null
        return Converter { file -> file.asRequestBody(pendingContentType.get()?.toMediaTypeOrNull()) }
    }
}
