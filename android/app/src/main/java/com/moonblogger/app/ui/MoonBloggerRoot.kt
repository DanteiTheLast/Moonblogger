package com.moonblogger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moonblogger.app.R
import com.moonblogger.app.data.auth.SessionState
import com.moonblogger.app.di.AppContainer
import com.moonblogger.app.ui.navigation.AuthNavHost
import com.moonblogger.app.ui.navigation.MainNavHost

/**
 * Raíz de la navegación: el flujo mostrado depende del estado de sesión.
 *  - Loading  → pantalla de carga (inicialización de sesión al arrancar).
 *  - LoggedOut → flujo de login.
 *  - LoggedIn  → flujo principal (lista, detalle, editor).
 *
 * Al cambiar de estado (login, logout, refresh fallido) el NavHost se
 * sustituye y sus ViewModels quedan liberados.
 */
@Composable
fun MoonBloggerRoot(container: AppContainer) {
    val appViewModel: AppViewModel = viewModel(factory = container.viewModelFactory)
    val sessionState by appViewModel.sessionState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        appViewModel.initialize()
    }

    when (sessionState) {
        SessionState.Loading -> LoadingScreen()
        SessionState.LoggedOut -> AuthNavHost(factory = container.viewModelFactory)
        SessionState.LoggedIn -> MainNavHost(
            factory = container.viewModelFactory,
            onLogout = appViewModel::logout,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            CircularProgressIndicator()
        }
    }
}
