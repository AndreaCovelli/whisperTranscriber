package com.andreacovelli.whispertranscriber.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.summary.SummaryStyle

/** The summary styles a user can pick for one summary; [defaultStyle] is the one chosen in settings. */
data class SummaryStyleChoices(
    val defaultStyle: SummaryStyle = SummaryStyle.AUTOMATIC,
    val customPromptAvailable: Boolean = false,
) {
    val styles: List<SummaryStyle>
        get() = SummaryStyle.entries.filter { it != SummaryStyle.CUSTOM || customPromptAvailable }
}

@StringRes
fun summaryStyleLabel(style: SummaryStyle): Int = when (style) {
    SummaryStyle.AUTOMATIC -> R.string.summary_style_automatic
    SummaryStyle.BULLET_POINTS -> R.string.summary_style_bullet_points
    SummaryStyle.ACTION_ITEMS -> R.string.summary_style_action_items
    SummaryStyle.MEETING_MINUTES -> R.string.summary_style_meeting_minutes
    SummaryStyle.CUSTOM -> R.string.summary_style_custom
}

/** Shows [anchor] and, while [expanded], a menu of [choices] with the default style checked. */
@Composable
internal fun SummaryStyleMenu(
    choices: SummaryStyleChoices,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SummaryStyle) -> Unit,
    anchor: @Composable () -> Unit,
) {
    Box {
        anchor()
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            choices.styles.forEach { style ->
                DropdownMenuItem(
                    text = { Text(stringResource(summaryStyleLabel(style))) },
                    trailingIcon = if (style == choices.defaultStyle) {
                        {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                stringResource(R.string.summary_style_default_description),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onDismiss()
                        onSelect(style)
                    },
                )
            }
        }
    }
}
