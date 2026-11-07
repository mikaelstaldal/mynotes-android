package nu.staldal.mynotes.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.ConnectivityObserver
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.preferences.UserPreferences

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.create(context)

    @Provides
    @Singleton
    fun artifacts(
        @ApplicationContext context: Context,
        database: AppDatabase,
        prefs: UserPreferences,
        connectivity: ConnectivityObserver,
        client: RetrofitClient,
    ): ArtifactRepository = ArtifactRepository(
        context,
        database,
        isOnlineProvider = { !prefs.serverConfig.first().offlineMode && connectivity.isOnline.first() },
        apiProvider = {
            val config = prefs.serverConfig.first()
            client.getApiService(config.baseUrl, config.username, config.password)
        },
    )

    @Provides
    @Singleton
    fun notes(
        database: AppDatabase,
        artifacts: ArtifactRepository,
        prefs: UserPreferences,
        client: RetrofitClient,
    ): NoteRepository =
        NoteRepository(
            database,
            artifacts,
            apiProvider = {
                val config = prefs.serverConfig.first()
                client.getApiService(config.baseUrl, config.username, config.password)
            },
            baseUrlProvider = { prefs.serverConfig.first().baseUrl },
        )
}
