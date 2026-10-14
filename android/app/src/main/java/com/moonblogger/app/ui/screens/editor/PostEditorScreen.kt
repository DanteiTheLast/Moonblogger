package com.moonblogger.app.ui.screens.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.moonblogger.app.ui.components.PixelAlertDialog as AlertDialog
import com.moonblogger.app.ui.components.PixelButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.moonblogger.app.ui.components.PixelOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.moonblogger.app.R
import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.data.model.CarouselTransition

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
    var showExitDialog by remember { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (!state.isSaving && !state.isInspecting) {
            if (state.hasChanges) showExitDialog = true else onBack()
        }
    }
    BackHandler(onBack = requestBack)
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Salir del editor") },
            text = { Text(if (!state.localCopyAvailable) "No hay una copia local actualizada. Sigue escribiendo o guarda en el servidor antes de salir; descartar puede perder el escrito. Los cambios ya guardados en el servidor no se revierten." else "Puedes conservar la copia local del escrito y volver después. Las fotos que aún no han subido deben elegirse de nuevo. Descartar la copia local no revierte los cambios ya guardados en el servidor.") },
            confirmButton = { TextButton(enabled = state.localCopyAvailable, onClick = { showExitDialog = false; onBack() }) { Text("Conservar y salir") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { if (viewModel.discardLocal()) { showExitDialog = false; onBack() } }) { Text("Descartar copia local") }
                    TextButton(onClick = { showExitDialog = false }) { Text("Seguir escribiendo") }
                }
            },
        )
    }
    val photoPicker = rememberLauncherForActivityResult(
        contract = PickMultipleVisualMedia(maxItems = 10),
        onResult = viewModel::onPhotosSelected,
    )

    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.loadTags()
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
                    IconButton(onClick = requestBack, enabled = !state.isSaving && !state.isInspecting) {
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
            } else if (state.loadError != null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(requireNotNull(state.loadError), color = MaterialTheme.colorScheme.error)
                    if (state.localRecoveryError) {
                        Button(onClick = viewModel::discardFailedRecovery) { Text("Descartar copia local ilegible") }
                    } else Button(onClick = viewModel::retryLoad) { Text("Reintentar carga") }
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
                    state.localNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.needsPhotoReview) {
                        OutlinedButton(onClick = viewModel::acknowledgeMissingPhotos) { Text("Revisé las fotos recuperadas") }
                    }
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

                    com.moonblogger.app.ui.components.TagSelector(state.tags, state.tagCatalog, state.tagsLoading, state.tagsError,
                        !state.isSaving && !state.isLoading, viewModel::toggleTag, viewModel::loadTags)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.status == PostStatus.DRAFT,
                            onClick = { viewModel.onStatusChange(PostStatus.DRAFT) },
                            shape = MaterialTheme.shapes.small,
                            enabled = !state.isSaving,
                        ) {
                            Text(stringResource(R.string.editor_status_draft))
                        }
                        SegmentedButton(
                            selected = state.status == PostStatus.PUBLISHED,
                            onClick = { viewModel.onStatusChange(PostStatus.PUBLISHED) },
                            shape = MaterialTheme.shapes.small,
                            enabled = !state.isSaving,
                        ) {
                            Text(stringResource(R.string.editor_status_published))
                        }
                    }

                    Text(
                        text = "Fotos (${state.media.size}/10)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Añade JPEG, PNG o WebP estáticos de hasta 32 MiB. Optimizamos las fotos a 2560 px sin modificar los originales. Los vídeos aún no están disponibles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        },
                        enabled = !state.isSaving && !state.isInspecting && state.media.size < 10,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isInspecting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Elegir fotos")
                        }
                    }

                    if (state.isInspecting) {
                        Text("Procesando fotos seleccionadas…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    state.media.forEachIndexed { index, media ->
                        MediaEditorCard(
                            item = media,
                            index = index,
                            count = state.media.size,
                            enabled = !state.isSaving && !state.isInspecting,
                            onMoveUp = { viewModel.moveMedia(media.key, -1) },
                            onMoveDown = { viewModel.moveMedia(media.key, 1) },
                            onSetCover = { viewModel.setCover(media.key) },
                            onRemove = { viewModel.removeMedia(media.key) },
                            onAltTextChange = { viewModel.onAltTextChange(media.key, it) },
                            onCaptionChange = { viewModel.onCaptionChange(media.key, it) },
                        )
                    }

                    if (state.media.isNotEmpty()) {
                        Text("Transición del carrusel", style = MaterialTheme.typography.titleSmall)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            CarouselTransition.entries.forEachIndexed { index, transition ->
                                SegmentedButton(
                                    selected = state.transition == transition,
                                    onClick = { viewModel.onTransitionChange(transition) },
                                    shape = MaterialTheme.shapes.small,
                                    enabled = !state.isSaving,
                                ) { Text(transition.label()) }
                            }
                        }
                    }

                    state.uploadProgress?.let { (completed, total) ->
                        if (total > 0) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { completed.toFloat() / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    state.uploadMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun MediaEditorCard(
    item: EditorMedia,
    index: Int,
    count: Int,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetCover: () -> Unit,
    onRemove: () -> Unit,
    onAltTextChange: (String) -> Unit,
    onCaptionChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.localPhoto != null) {
                AsyncImage(
                    model = item.localPhoto.uri,
                    contentDescription = "Vista previa de foto ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (item.kind == "video") "Vídeo" else "Foto")
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(if (item.isCover) "Portada" else "Foto ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (item.isLocal) "Nueva imagen" else "Imagen existente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove, enabled = enabled) { Text("Quitar") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onMoveUp, enabled = enabled && index > 0) { Text("Mover arriba") }
            TextButton(onClick = onMoveDown, enabled = enabled && index < count - 1) { Text("Mover abajo") }
            TextButton(onClick = onSetCover, enabled = enabled && !item.isCover) { Text("Usar de portada") }
        }
        OutlinedTextField(
            value = item.altText,
            onValueChange = onAltTextChange,
            label = { Text("Texto alternativo") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = item.caption,
            onValueChange = onCaptionChange,
            label = { Text("Pie de foto") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun CarouselTransition.label(): String = when (this) {
    CarouselTransition.SLIDE -> "Slide"
    CarouselTransition.FADE -> "Fade"
    CarouselTransition.BUBBLE -> "Bubble"
    CarouselTransition.NONE -> "Sin transición"
}
