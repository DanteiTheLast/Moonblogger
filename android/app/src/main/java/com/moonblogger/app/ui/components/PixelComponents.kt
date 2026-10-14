package com.moonblogger.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moonblogger.app.data.model.Tag
import com.moonblogger.app.ui.theme.*

/** A crisp offset shadow; layout reserves space so cards do not overlap. */
fun Modifier.pixelShadow(color: Color): Modifier = padding(end = 4.dp, bottom = 4.dp).drawBehind {
    val offset = 4.dp.toPx(); drawRect(color, topLeft = Offset(offset, offset))
}
@Composable
fun ThemePicker() {
    val controller = LocalThemeController.current ?: return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Tema") }
    if (open) PixelAlertDialog(onDismissRequest = { open = false }, title = { Text("Apariencia") }, text = {
        Column { ThemePreference.entries.forEach { value ->
            Row { RadioButton(selected = controller.preference == value, onClick = { controller.select(value) }); TextButton(onClick = { controller.select(value) }) { Text(value.label) } }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Listo") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Hashtags(tags: List<Tag>) {
    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { Text("#${it.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}
@Composable
fun DateBadge(text: String) {
    val colors = LocalMoonAccents.current
    Surface(color = colors.structural, contentColor = colors.onStructural, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelector(tags: List<Tag>, catalog: List<Tag>, loading: Boolean, error: String?, enabled: Boolean, onToggle: (String) -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Etiquetas · opcional (${tags.size}/5)", style = MaterialTheme.typography.titleMedium)
        Text("Elige hasta cinco temas para encontrar esta publicación.", style = MaterialTheme.typography.bodySmall)
        if (loading) Text("Cargando etiquetas…", style = MaterialTheme.typography.bodyMedium)
        if (error != null) { Text("No se cargó el catálogo. Tus etiquetas se conservarán.", color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry, enabled = enabled) { Text("Reintentar") } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (catalog + tags).distinctBy { it.slug }.forEach { tag ->
                val selected = tags.any { it.slug == tag.slug }
                FilterChip(selected = selected, onClick = { onToggle(tag.slug) }, label = { Text("${if (selected) "✓ " else ""}#${tag.name}") },
                    enabled = enabled && !loading && error == null && (selected || tags.size < 5),
                    shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
fun PixelButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = shape,
        modifier = modifier.heightIn(min = 48.dp).pixelShadow(LocalMoonAccents.current.shadow).border(2.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp), content = content)
}
@Composable
fun PixelOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = shape, modifier = modifier.heightIn(min = 48.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}

@Composable
fun PixelAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton, title = title, text = text,
        shape = MaterialTheme.shapes.large, tonalElevation = 0.dp, containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.pixelShadow(LocalMoonAccents.current.largeShadow).border(2.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large))
}
