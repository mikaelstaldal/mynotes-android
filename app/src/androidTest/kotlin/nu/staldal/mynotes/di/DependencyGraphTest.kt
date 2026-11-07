package nu.staldal.mynotes.di

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import nu.staldal.mynotes.MyNotesApplication
import nu.staldal.mynotes.provider.NotesProvider
import org.junit.Assert.assertSame
import org.junit.Test

class DependencyGraphTest {
    @Test
    fun providerAndApplicationShareSingletonDependencies() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MyNotesApplication
        val first = EntryPointAccessors.fromApplication(application, NotesProvider.Dependencies::class.java)
        val second = EntryPointAccessors.fromApplication(application, NotesProvider.Dependencies::class.java)

        assertSame(first.database(), second.database())
        assertSame(first.notes(), second.notes())
        assertSame(first.artifacts(), second.artifacts())
        assertSame(application.workerFactory, application.workManagerConfiguration.workerFactory)
    }
}
