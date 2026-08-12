package com.moonblogger.app.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.auth.SessionManager
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotBlank() && !isLoading
}

/**
 * Login. Al tener éxito, [SessionManager.onLoginSuccess] cambia el estado de
 * sesión a LoggedIn; [com.moonblogger.app.ui.MoonBloggerRoot] navega al flujo
 * principal y esta pantalla (y su ViewModel) se liberan.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun login() {
        val username = _uiState.value.username.trim()
        val password = _uiState.value.password
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "Introduce usuario y contraseña.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authRepository.login(username, password)
                .onSuccess { tokens ->
                    _uiState.update { it.copy(isLoading = false) }
                    sessionManager.onLoginSuccess(tokens.access, tokens.refresh)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }
}
