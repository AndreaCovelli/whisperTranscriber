package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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

internal fun buildPrefixMatchRegex(tokens: List<String>): Regex? {
    if (tokens.isEmpty()) return null
    val pattern = tokens
        .distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    return Regex("""\b(?:$pattern)""", RegexOption.IGNORE_CASE)
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

@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    actions: HistoryItemCardActions,
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
            HistoryItemHeader(
                title = if (item.displayName.isBlank()) stringResource(R.string.job_audio_file) else null,
                highlightedTitle = highlightedDisplayName.takeIf { item.displayName.isNotBlank() },
                onDelete = actions.delete,
            )
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
            if (hasTranscript || hasSummary) {
                Spacer(Modifier.height(8.dp))
            }
            HistoryItemBody(
                item = item,
                expanded = expanded,
                onToggleExpand = onToggleExpand,
                highlightedText = highlightedText,
                highlightedSummary = highlightedSummary,
                actions = actions,
            )
        }
    }
}

@Composable
private fun HistoryItemHeader(title: String?, highlightedTitle: AnnotatedString?, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            highlightedTitle != null -> {
                Text(
                    highlightedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            title != null -> {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
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
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun HistoryItemBody(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    highlightedText: AnnotatedString,
    highlightedSummary: AnnotatedString,
    actions: HistoryItemCardActions,
) {
    val transcriptOverflow = rememberOverflowState(item.id, "transcript", highlightedText)
    val summaryOverflow = rememberOverflowState(item.id, "summary", highlightedSummary)
    val hasTranscript = item.text.isNotBlank()
    val hasSummary = item.summary.isNotBlank()
    val showExpandControl = expanded || transcriptOverflow.value || summaryOverflow.value

    if (hasTranscript) {
        CardSectionHeader(
            title = stringResource(R.string.job_transcription_title),
            onCopy = actions.copyTranscript,
            onShare = actions.shareTranscript,
            onClear = actions.clearTranscript,
            shareDescription = stringResource(R.string.share_transcription_title),
            clearDescription = stringResource(R.string.clear_transcription),
        )
        Spacer(Modifier.height(4.dp))
        SelectableHistoryText(
            text = highlightedText,
            expanded = expanded,
            collapsedMaxLines = 4,
            styleColor = Color.Unspecified,
            overflowState = transcriptOverflow,
            onExpand = onToggleExpand,
        )
    }
    if (hasSummary) {
        SummaryContent(
            item = item,
            summary = highlightedSummary,
            expanded = expanded,
            overflowState = summaryOverflow,
            onExpand = onToggleExpand,
            actions = actions,
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
    overflowState: MutableState<Boolean>,
    onExpand: () -> Unit,
    actions: HistoryItemCardActions,
) {
    Spacer(Modifier.height(if (expanded) 12.dp else 6.dp))
    CardSectionHeader(
        title = stringResource(R.string.job_summary_title),
        onCopy = actions.copySummary,
        onShare = actions.shareSummary,
        onClear = actions.clearSummary,
        shareDescription = stringResource(R.string.share_summary_title),
        clearDescription = stringResource(R.string.clear_summary),
    )
    if (item.summaryModel.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
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
        overflowState = overflowState,
        onExpand = onExpand,
    )
}

@Composable
private fun SelectableHistoryText(
    text: AnnotatedString,
    expanded: Boolean,
    collapsedMaxLines: Int,
    styleColor: Color,
    overflowState: MutableState<Boolean>,
    onExpand: () -> Unit,
) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            color = styleColor,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!expanded && overflowState.value) {
                        Modifier.clickable(onClick = onExpand)
                    } else {
                        Modifier
                    },
                ),
            onTextLayout = { result ->
                if (!expanded) {
                    overflowState.value = result.hasVisualOverflow
                }
            },
        )
    }
}

@Composable
private fun rememberOverflowState(itemId: Int, section: String, text: AnnotatedString): MutableState<Boolean> =
    remember(itemId, section, text) { mutableStateOf(false) }
