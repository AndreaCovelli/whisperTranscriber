package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.R

@Composable
internal fun CardSectionHeader(
    title: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    shareDescription: String,
    saveDescription: String,
    clearDescription: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            trailing()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCopy) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy_type, title))
                }
                IconButton(onClick = onShare) {
                    Icon(painterResource(R.drawable.ic_share), shareDescription)
                }
                IconButton(onClick = onSave) {
                    Icon(painterResource(R.drawable.ic_download), saveDescription)
                }
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_delete_sweep),
                        contentDescription = clearDescription,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
