package com.moonblogger.app.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.moonblogger.app.ui.AppViewModel
import com.moonblogger.app.ui.screens.detail.PostDetailViewModel
import com.moonblogger.app.ui.screens.editor.PostEditorViewModel
import com.moonblogger.app.ui.screens.login.LoginViewModel
import com.moonblogger.app.ui.screens.posts.PostsViewModel

/**
 * Factory manual de ViewModels (D7, sin Hilt). Los argumentos de navegación
 * llegan vía SavedStateHandle, que Navigation Compose rellena con los
 * nav-args del destino:
 *  - `post/{postId}` → postId Long (obligatorio)
 *  - `editor?postId={postId}` → postId Long, -1 si no se pasa (creación)
 */
class MoonBloggerViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val savedStateHandle = extras.createSavedStateHandle()
        return when {
            modelClass.isAssignableFrom(AppViewModel::class.java) ->
                AppViewModel(container.sessionManager)

            modelClass.isAssignableFrom(LoginViewModel::class.java) ->
                LoginViewModel(container.authRepository, container.sessionManager)

            modelClass.isAssignableFrom(PostsViewModel::class.java) ->
                PostsViewModel(container.postRepository)

            modelClass.isAssignableFrom(PostDetailViewModel::class.java) ->
                PostDetailViewModel(
                    repository = container.postRepository,
                    postId = requireNotNull(savedStateHandle["postId"]),
                )

            modelClass.isAssignableFrom(PostEditorViewModel::class.java) ->
                PostEditorViewModel(
                    repository = container.postRepository,
                    mediaRepository = container.mediaRepository,
                    photoSourceProvider = container.photoSourceProvider,
                    postId = (savedStateHandle.get<Long>("postId") as Long?)?.takeIf { it >= 0 },
                )

            else -> throw IllegalArgumentException("ViewModel desconocido: ${modelClass.name}")
        } as T
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        create(modelClass, CreationExtras.Empty)
}
