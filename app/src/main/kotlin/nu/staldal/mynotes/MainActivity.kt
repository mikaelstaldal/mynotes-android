package nu.staldal.mynotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import nu.staldal.mynotes.provider.NotesContract
import nu.staldal.mynotes.ui.navigation.NavGraph
import nu.staldal.mynotes.ui.theme.MyNotesTheme

class MainActivity : ComponentActivity() {

    /**
     * Route an incoming `ACTION_VIEW` asked for, consumed once by [NavGraph]. Held as state rather
     * than read out of the intent during composition, so a second intent arriving at the already
     * running activity ([onNewIntent]) navigates as well.
     */
    private val deepLinkRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkRoute.value = routeFor(intent)

        setContent {
            MyNotesTheme {
                NavGraph(
                    deepLinkRoute = deepLinkRoute.value,
                    onDeepLinkHandled = { deepLinkRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkRoute.value = routeFor(intent)
    }

    private fun routeFor(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return routeForUri(intent.data ?: return null)
    }
}

/**
 * The in-app destination a [NotesContract] URI names, or null for anything else. This is how a
 * consumer of [nu.staldal.mynotes.provider.NotesProvider] hands the user back to this app — to
 * follow a wikilink out of a note it embedded, or to edit a note it can only read.
 */
internal fun routeForUri(uri: Uri): String? {
    if (uri.authority != NotesContract.AUTHORITY) return null
    val segments = uri.pathSegments
    if (segments.size != 2) return null
    val slug = segments[1].takeIf { it.isNotEmpty() } ?: return null
    return when (segments[0]) {
        NotesContract.PATH_NOTES -> "note/$slug"
        NotesContract.PATH_TAGS -> "notes?tag=$slug"
        else -> null
    }
}
