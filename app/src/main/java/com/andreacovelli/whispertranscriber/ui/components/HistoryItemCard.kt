package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.extractHistorySearchTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun buildHighlightedText(text: String, query: String, background: Color, onBackground: Color): AnnotatedString {
    val tokens = extractHistorySearchTokens(query)
    if (tokens.isEmpty()) return AnnotatedString(text)

    val matches = tokens
        .flatMap { token ->
            buildList {
                var startIndex = text.indexOf(token, ignoreCase = true)
                while (startIndex >= 0) {
                    add(startIndex until (startIndex + token.length))
                    startIndex = text.indexOf(token, startIndex + token.length, ignoreCase = true)
                }
            }
        }
        .sortedBy { it.first }

    if (matches.isEmpty()) return AnnotatedString(text)

    val mergedMatches = buildList {
        var current = matches.first()
        matches.drop(1).forEach { next ->
            current = if (next.first <= current.last + 1) {
                current.first..maxOf(current.last, next.last)
            } else {
                add(current)
                next
            }
        }
        add(current)
    }

    return buildAnnotatedString {
        var cursor = 0
        mergedMatches.forEach { range ->
            if (range.first > cursor) {
                append(text, cursor, range.first)
            }
            withStyle(SpanStyle(background = background, color = onBackground)) {
                append(text, range.first, range.last + 1)
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text, cursor, text.length)
        }
    }
}

@Composable
private fun rememberHighlightedText(text: String, query: String): AnnotatedString {
    val background = MaterialTheme.colorScheme.primaryContainer
    val onBackground = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, query, background, onBackground) {
        buildHighlightedText(text, query, background, onBackground)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: (String) -> Unit,
    onClearSummary: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    searchQuery: String = "",
) {
    val formattedDate = remember(item.timestamp) {
        DateTimeFormatter.ofPattern("MMM dd, yyyy - HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault()))
    }
    val displayModel = remember(item.model) { item.model.ifBlank { "Unknown" } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onToggleExpand),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ),
        ) {
            if (item.displayName.isNotBlank()) {
                Text(
                    rememberHighlightedText(item.displayName, searchQuery),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Language: ${item.language}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Model: $displayModel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                Text(rememberHighlightedText(item.text, searchQuery), style = MaterialTheme.typography.bodyMedium)
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rememberHighlightedText(item.summary, searchQuery),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    rememberHighlightedText(item.text, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rememberHighlightedText(item.summary, searchQuery),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Show more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val copyActions = buildList {
                    add("text" to item.text)
                    if (item.summary.isNotBlank()) add("summary" to item.summary)
                }
                copyActions.forEach { (type, content) ->
                    TextButton(onClick = { onCopy(content) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.copy_type, type), maxLines = 1, softWrap = false)
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, stringResource(R.string.share_item_description))
                }
                if (item.summary.isNotBlank()) {
                    IconButton(onClick = onClearSummary) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.clear_summary),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.delete_item_description),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
