package nu.staldal.mynotes.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import nu.staldal.mynotes.ui.conflict.ConflictDetailScreen
import nu.staldal.mynotes.ui.conflict.ConflictListScreen
import nu.staldal.mynotes.ui.note.NoteDetailScreen
import nu.staldal.mynotes.ui.note.NoteFormScreen
import nu.staldal.mynotes.ui.note.NoteListScreen
import nu.staldal.mynotes.ui.settings.SettingsScreen

/**
 * @param deepLinkRoute route an incoming `ACTION_VIEW` asked for (see [nu.staldal.mynotes.routeForUri]),
 *   which *replaces* the note list as the only entry on the back stack. Null for a normal launch.
 * @param onDeepLinkHandled called once [deepLinkRoute] has been navigated to, so it is not
 *   re-navigated on every recomposition.
 */
@Composable
fun NavGraph(
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val activity = LocalActivity.current

    LaunchedEffect(deepLinkRoute) {
        if (deepLinkRoute != null) {
            // Popped inclusively rather than stacked on top of the note list: a deep link comes
            // from another app (MyCal opening the note linked to an event, or a wikilink inside a
            // note it embedded), and Back there belongs to the app that sent the user here, not to
            // a note list they never asked for.
            navController.navigate(deepLinkRoute) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            }
            onDeepLinkHandled()
        }
    }

    // With the deep-linked screen as the only entry there is nothing to pop, so Back leaves the
    // app instead of dead-ending on it. System Back already does this — NavHost only intercepts it
    // while the stack has something to pop — this is the same for the toolbar's Back button.
    val navigateBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
        else activity?.finish()
    }

    NavHost(navController = navController, startDestination = "notes") {
        composable(
            "notes?tag={tag}",
            arguments = listOf(navArgument("tag") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            NoteListScreen(
                initialTag = backStackEntry.arguments?.getString("tag"),
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToNote = { slug -> navController.navigate("note/$slug") },
                onNavigateToNewNote = { navController.navigate("note/new") },
                onNavigateToConflicts = { navController.navigate("conflicts") },
            )
        }

        composable(
            "note/{slug}",
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slug = backStackEntry.arguments?.getString("slug") ?: return@composable
            NoteDetailScreen(
                slug = slug,
                onNavigateBack = navigateBack,
                onNavigateToEdit = { s -> navController.navigate("note/edit/$s") },
                onNavigateToNote = { s -> navController.navigate("note/$s") },
                onNavigateToTag = { s -> navController.navigate("notes?tag=$s") },
            )
        }

        composable("note/new") {
            NoteFormScreen(
                slug = null,
                onNavigateBack = navigateBack,
            )
        }

        composable(
            "note/edit/{slug}",
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slug = backStackEntry.arguments?.getString("slug") ?: return@composable
            NoteFormScreen(
                slug = slug,
                onNavigateBack = navigateBack,
            )
        }

        composable("conflicts") {
            ConflictListScreen(
                onNavigateBack = navigateBack,
                onNavigateToConflict = { slug -> navController.navigate("conflict/$slug") },
            )
        }

        composable(
            "conflict/{slug}",
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slug = backStackEntry.arguments?.getString("slug") ?: return@composable
            ConflictDetailScreen(
                slug = slug,
                onNavigateBack = navigateBack,
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = navigateBack,
                onSaved = { navController.popBackStack() },
                onSignedOut = { navController.popBackStack() },
            )
        }
    }
}
