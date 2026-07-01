package nu.staldal.mynotes.data.api

import nu.staldal.mynotes.BuildConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private var currentBaseUrl: String? = null
    private var currentUsername: String? = null
    private var currentPassword: String? = null
    private var apiService: DefaultApi? = null

    fun getApiService(baseUrl: String, username: String, password: String): DefaultApi? {
        if (baseUrl.isBlank()) return null

        val trimmedUrl = baseUrl.trimEnd('/')
        val withApiPath = if (trimmedUrl.endsWith("/api/v1")) trimmedUrl else "$trimmedUrl/api/v1"
        val normalizedUrl = "$withApiPath/"

        if (normalizedUrl == currentBaseUrl && username == currentUsername && password == currentPassword && apiService != null) {
            return apiService
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (username.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(username, password))
                }
                chain.proceed(requestBuilder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("Authorization")
                    })
                }
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(FileRequestBodyConverterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(DefaultApi::class.java)
        currentBaseUrl = normalizedUrl
        currentUsername = username
        currentPassword = password
        return apiService
    }
}
