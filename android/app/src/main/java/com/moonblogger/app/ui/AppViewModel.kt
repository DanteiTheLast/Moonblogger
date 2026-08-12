package com.moonblogger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.auth.SessionManager
import com.moonblogger.app.data.auth.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ViewModel raíz: expone el estado de sesión y cierra sesión. */
class AppViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = sessionManager.state

    /** Comprueba la sesión al arrancar (refresca el access si caducó). */
    fun initialize() {
        viewModelScope.launch { sessionManager.initialize() }
    }

    fun logout() {
        sessionManager.logout()
    }
}
