package com.moonblogger.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.moonblogger.app.R
import com.moonblogger.app.data.model.PostMedia
import com.moonblogger.app.data.model.MediaReadUrl

@Composable
fun PostMediaViewer(media: List<PostMedia>, readUrls: List<MediaReadUrl>, error: String? = null) {
    if (media.isEmpty()) return
    val items = remember(media) { media.filter { it.state == "ready" }.sortedWith(compareBy<PostMedia> { it.position ?: Int.MAX_VALUE }.thenBy { !it.is_cover }) }
    val urls = remember(readUrls) { readUrls.associateBy { it.id } }
    var index by remember(items) { mutableIntStateOf(items.indexOfFirst { it.is_cover }.coerceAtLeast(0)) }
    if (items.isEmpty() || urls.isEmpty()) {
        Text(error ?: stringResource(R.string.media_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        return
    }
    val item = items[index.coerceIn(items.indices)]
    val source = urls[item.id]?.let { if (item.kind == "video") it.poster_url else it.url }
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (source == null) Text(stringResource(R.string.media_error), modifier = Modifier.padding(24.dp))
        else AsyncImage(model = source, contentDescription = item.alt_text.ifBlank { item.caption }, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp).aspectRatio(((item.width ?: 1).toFloat() / (item.height ?: 1)).coerceIn(.5f, 2f)))
        if (items.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = index > 0, onClick = { index-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.media_previous)) }
            Text("${index + 1}/${items.size}", modifier = Modifier.align(Alignment.CenterVertically))
            IconButton(enabled = index < items.lastIndex, onClick = { index++ }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.media_next)) }
        }
    }
    if (item.caption.isNotBlank()) Text(item.caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}
