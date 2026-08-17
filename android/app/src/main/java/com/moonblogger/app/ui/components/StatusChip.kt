package com.moonblogger.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moonblogger.app.R
import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.ui.theme.MoonLavender
import com.moonblogger.app.ui.theme.MoonText

/**
 * Chip redondo con el estado de la publicación: borrador (lavanda) o
 * publicada (rosa), equivalente al badge de la web.
 */
@Composable
fun StatusChip(
    status: PostStatus,
    modifier: Modifier = Modifier,
) {
    val label = when (status) {
        PostStatus.DRAFT -> stringResource(R.string.status_draft)
        PostStatus.PUBLISHED -> stringResource(R.string.status_published)
    }
    val containerColor = when (status) {
        PostStatus.DRAFT -> MoonLavender
        PostStatus.PUBLISHED -> MaterialTheme.colorScheme.primaryContainer
    }

    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = MoonText,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
