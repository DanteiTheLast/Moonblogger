package com.moonblogger.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moonblogger.app.ui.screens.detail.PostDetailScreen
import com.moonblogger.app.ui.screens.detail.PostDetailViewModel
import com.moonblogger.app.ui.screens.editor.PostEditorScreen
import com.moonblogger.app.ui.screens.editor.PostEditorViewModel
import com.moonblogger.app.ui.screens.login.LoginScreen
import com.moonblogger.app.ui.screens.login.LoginViewModel
import com.moonblogger.app.ui.screens.posts.PostsScreen
import com.moonblogger.app.ui.screens.posts.PostsViewModel

/** Rutas de la aplicación. El editor recibe postId opcional (-1 = crear). */
object Routes {
    const val LOGIN = "login"
    const val POSTS = "posts"
    const val POST_DETAIL = "post/{postId}"
    const val POST_EDITOR = "editor?postId={postId}"

    const val ARG_POST_ID = "postId"
    const val EDITOR_NO_POST_ID = -1L

    fun postDetail(postId: Long): String = "post/$postId"

    fun postEditor(postId: Long? = null): String =
        if (postId == null) "editor" else "editor?postId=$postId"
}

/** Flujo no autenticado: solo login. */
@Composable
fun AuthNavHost(factory: ViewModelProvider.Factory) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            val viewModel: LoginViewModel = viewModel(factory = factory)
            LoginScreen(viewModel = viewModel)
        }
    }
}

/** Flujo autenticado: lista → detalle → editor. */
@Composable
fun MainNavHost(
    factory: ViewModelProvider.Factory,
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.POSTS) {

        composable(Routes.POSTS) {
            val viewModel: PostsViewModel = viewModel(factory = factory)
            PostsScreen(
                viewModel = viewModel,
                onPostClick = { navController.navigate(Routes.postDetail(it)) },
                onNewPost = { navController.navigate(Routes.postEditor()) },
                onLogout = onLogout,
            )
        }

        composable(
            route = Routes.POST_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_POST_ID) { type = NavType.LongType }),
        ) {
            val viewModel: PostDetailViewModel = viewModel(factory = factory)
            PostDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = {
                    val id = viewModel.uiState.value.post?.id ?: return@PostDetailScreen
                    navController.navigate(Routes.postEditor(id))
                },
            )
        }

        composable(
            route = Routes.POST_EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_POST_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.EDITOR_NO_POST_ID
                },
            ),
        ) {
            val viewModel: PostEditorViewModel = viewModel(factory = factory)
            PostEditorScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
    }
}
