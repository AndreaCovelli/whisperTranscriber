package com.andreacovelli.whispertranscriber.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.andreacovelli.whispertranscriber.UserMessage

/** Resolves the message for display, again whenever the configuration (such as the locale) changes. */
@Composable
@ReadOnlyComposable
fun UserMessage.asString(): String {
    LocalResources.current
    return resolve(LocalContext.current)
}
