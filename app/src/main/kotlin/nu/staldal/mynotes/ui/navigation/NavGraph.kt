package nu.staldal.mynotes.ui.navigation

import androidx.compose.runtime.Composable
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

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "notes") {
        composable("notes") {
            NoteListScreen(
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { s -> navController.navigate("note/edit/$s") },
            )
        }

        composable("note/new") {
            NoteFormScreen(
                slug = null,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            "note/edit/{slug}",
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { backStackEntry ->
            val slug = backStackEntry.arguments?.getString("slug") ?: return@composable
            NoteFormScreen(
                slug = slug,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable("conflicts") {
            ConflictListScreen(
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}
