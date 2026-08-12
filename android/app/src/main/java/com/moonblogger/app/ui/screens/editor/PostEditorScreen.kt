package com.moonblogger.app.ui.screens.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moonblogger.app.R
import com.moonblogger.app.data.model.PostStatus

/**
 * Crea o edita una publicación. En modo edición precarga los datos con
 * [PostEditorViewModel.start]. Al guardar, [PostEditorViewModel.isSaved] se
 * pone a true y la pantalla navega hacia atrás.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostEditorScreen(
    viewModel: PostEditorViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEdit) R.string.editor_title_edit else R.string.editor_title_new,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = { Text(stringResource(R.string.editor_field_title)) },
                        singleLine = true,
                        enabled = !state.isSaving,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.content,
                        onValueChange = viewModel::onContentChange,
                        label = { Text(stringResource(R.string.editor_field_content)) },
                        enabled = !state.isSaving,
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.status == PostStatus.DRAFT,
                            onClick = { viewModel.onStatusChange(PostStatus.DRAFT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            enabled = !state.isSaving,
                        ) {
                            Text(stringResource(R.string.editor_status_draft))
                        }
                        SegmentedButton(
                            selected = state.status == PostStatus.PUBLISHED,
                            onClick = { viewModel.onStatusChange(PostStatus.PUBLISHED) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            enabled = !state.isSaving,
                        ) {
                            Text(stringResource(R.string.editor_status_published))
                        }
                    }

                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = viewModel::save,
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.editor_save))
                        }
                    }
                }
            }
        }
    }
}
