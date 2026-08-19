package com.moonblogger.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.moonblogger.app.R
import com.moonblogger.app.data.model.*

@Composable
fun PostMediaViewer(
    media: List<PostMedia>, readUrls: List<MediaReadUrl>, error: String? = null,
    onRetry: () -> Unit = {},
) {
    val items = remember(media) { media.filter { it.state == "ready" }.sortedWith(compareBy<PostMedia> { it.position ?: Int.MAX_VALUE }.thenBy { !it.is_cover }) }
    if (items.isEmpty()) { Text(error ?: stringResource(R.string.media_empty), modifier = Modifier.padding(12.dp)); return }
    val urls = remember(readUrls) { readUrls.associateBy { it.id } }
    var index by remember(items) { mutableIntStateOf(items.indexOfFirst { it.is_cover }.coerceAtLeast(0)) }
    val item = items[index.coerceIn(items.indices)]
    val source = urls[item.id]?.let { if (item.kind == "video") it.poster_url else it.url }
    var failed by remember(item.id, source) { mutableStateOf(false) }
    var loading by remember(item.id, source) { mutableStateOf(source != null) }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            source == null || failed -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.media_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            else -> AsyncImage(model = source, contentDescription = item.alt_text.ifBlank { item.caption.ifBlank { stringResource(R.string.media_empty) } }, contentScale = ContentScale.Fit,
                onState = { loading = it is AsyncImagePainter.State.Loading; failed = it is AsyncImagePainter.State.Error }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp).aspectRatio(((item.width ?: 1).toFloat() / (item.height ?: 1)).coerceIn(.5f, 2f)))
        }
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        if (items.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = index > 0, onClick = { index-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_previous)) }
            Text("${index + 1}/${items.size}", Modifier.align(Alignment.CenterVertically))
            IconButton(enabled = index < items.lastIndex, onClick = { index++ }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.media_next)) }
        }
    }
    if (item.caption.isNotBlank()) Text(item.caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}
