package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun buildPrefixMatchRegex(tokens: List<String>): Regex? {
    if (tokens.isEmpty()) return null
    val pattern = tokens
        .distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    return Regex("""\b$pattern""", RegexOption.IGNORE_CASE)
}

private fun buildHighlightedText(
    text: String,
    tokens: List<String>,
    background: Color,
    onBackground: Color,
): AnnotatedString {
    val regex = buildPrefixMatchRegex(tokens) ?: return AnnotatedString(text)
    val matches = regex.findAll(text).map { it.range }.toList()

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
private fun rememberHighlightedText(text: String, tokens: List<String>): AnnotatedString {
    val background = MaterialTheme.colorScheme.primaryContainer
    val onBackground = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, tokens, background, onBackground) {
        buildHighlightedText(text, tokens, background, onBackground)
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
    searchTokens: List<String> = emptyList(),
) {
    val formattedDate = remember(item.timestamp) {
        DateTimeFormatter.ofPattern("MMM dd, yyyy - HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault()))
    }
    val unknownModelLabel = stringResource(R.string.history_item_unknown_model)
    val displayModel = remember(item.model, unknownModelLabel) { item.model.ifBlank { unknownModelLabel } }
    val normalizedSearchTokens = remember(searchTokens) {
        searchTokens.filter(String::isNotBlank).distinct()
    }
    val highlightedText = rememberHighlightedText(item.text, normalizedSearchTokens)
    val highlightedSummary = rememberHighlightedText(item.summary, normalizedSearchTokens)
    val highlightedDisplayName = rememberHighlightedText(item.displayName, normalizedSearchTokens)
    val hasTranscript = item.text.isNotBlank()
    val hasSummary = item.summary.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                Text(highlightedDisplayName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.history_item_language, item.language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.history_item_model, displayModel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            HistoryItemBody(
                item = item,
                expanded = expanded,
                onToggleExpand = onToggleExpand,
                highlightedText = highlightedText,
                highlightedSummary = highlightedSummary,
            )
            Spacer(Modifier.height(if (hasTranscript || hasSummary) 8.dp else 4.dp))
            HistoryItemActions(
                hasTranscript = hasTranscript,
                hasSummary = hasSummary,
                transcript = item.text,
                summary = item.summary,
                onCopy = onCopy,
                onShare = onShare,
                onClearSummary = onClearSummary,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun HistoryItemBody(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    highlightedText: AnnotatedString,
    highlightedSummary: AnnotatedString,
) {
    val hasTranscript = item.text.isNotBlank()
    val hasSummary = item.summary.isNotBlank()
    val showExpandControl = hasTranscript || hasSummary

    if (hasTranscript) {
        SelectableHistoryText(
            text = highlightedText,
            expanded = expanded,
            collapsedMaxLines = 4,
            styleColor = Color.Unspecified,
        )
    }
    if (hasSummary) {
        SummaryContent(
            item = item,
            summary = highlightedSummary,
            expanded = expanded,
            showTitle = expanded || !hasTranscript,
        )
    }
    if (showExpandControl) {
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleExpand) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.history_item_show_less
                        } else {
                            R.string.history_item_show_more
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SummaryContent(
    item: TranscriptionHistory,
    summary: AnnotatedString,
    expanded: Boolean,
    showTitle: Boolean,
) {
    Spacer(Modifier.height(if (expanded) 12.dp else 6.dp))
    if (showTitle) {
        Text(stringResource(R.string.job_summary_title), style = MaterialTheme.typography.titleSmall)
    }
    if (item.summaryModel.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.history_item_summary_model, item.summaryModel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(if (expanded) 4.dp else 2.dp))
    SelectableHistoryText(
        text = summary,
        expanded = expanded,
        collapsedMaxLines = 3,
        styleColor = if (expanded) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryItemActions(
    hasTranscript: Boolean,
    hasSummary: Boolean,
    transcript: String,
    summary: String,
    onCopy: (String) -> Unit,
    onShare: () -> Unit,
    onClearSummary: () -> Unit,
    onDelete: () -> Unit,
) {
    val copyActions = remember(transcript, summary, hasTranscript, hasSummary) {
        buildList {
            if (hasTranscript) add(R.string.copy_text_label to transcript)
            if (hasSummary) add(R.string.copy_summary_label to summary)
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        copyActions.forEach { (typeRes, content) ->
            TextButton(onClick = { onCopy(content) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.copy_type, stringResource(typeRes)), maxLines = 1, softWrap = false)
            }
        }
        if (hasTranscript || hasSummary) {
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, stringResource(R.string.share_item_description))
            }
        }
        if (hasSummary) {
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

@Composable
private fun SelectableHistoryText(
    text: AnnotatedString,
    expanded: Boolean,
    collapsedMaxLines: Int,
    styleColor: Color,
) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            color = styleColor,
        )
    }
}
