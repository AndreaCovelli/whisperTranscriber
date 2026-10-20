package com.andreacovelli.whispertranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainViewModel
import com.andreacovelli.whispertranscriber.ui.screens.HistoryScreen
import com.andreacovelli.whispertranscriber.ui.screens.MainScreen
import com.andreacovelli.whispertranscriber.ui.screens.SettingsScreen
import com.andreacovelli.whispertranscriber.ui.settings.SettingsMsg
import com.andreacovelli.whispertranscriber.ui.settings.SettingsViewModel
import com.andreacovelli.whispertranscriber.ui.theme.WhisperTranscriberTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object MainRoute

@Serializable
data object HistoryRoute

@Serializable
data object SettingsRoute

internal object SharedAudioIntentHandler {
    fun extractSharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val single = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                listOfNotNull(single)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val clipUris = intent.clipData?.let { clip ->
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                }.orEmpty()
                val extraUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                (clipUris + extraUris).distinct()
            }
            else -> emptyList()
        }
    }

    fun consumeSharedUris(intent: Intent?): Pair<List<Uri>, Intent?> {
        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) return emptyList<Uri>() to intent
        return uris to Intent()
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    private val mainViewModel: MainViewModel by viewModels()

    // Activity-scoped so the notification permission result can reach it from outside the settings screen.
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var pendingEnableBackgroundShareSummaryNotifications = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingEnableBackgroundShareSummaryNotifications) {
                settingsViewModel.dispatch(SettingsMsg.SetBackgroundShareSummaryNotificationsEnabled(granted))
                pendingEnableBackgroundShareSummaryNotifications = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEnableBackgroundShareSummaryNotifications =
            savedInstanceState?.getBoolean(PENDING_NOTIFICATION_INTENT) ?: false
        if (savedInstanceState == null) maybeRequestNotificationPermission()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(mainViewModel.events, settingsViewModel.events).collect { messageId ->
                    Toast.makeText(this@MainActivity, messageId, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (savedInstanceState == null) {
            consumeBackgroundShareResultNotificationIntent(intent)
            consumeSharedAudioIntent(intent)
        }

        setContent {
            WhisperTranscriberTheme {
                val navController = rememberNavController()
                val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                val navigationRequest = uiState.mainNavigationRequest
                LaunchedEffect(navigationRequest?.token) {
                    navigationRequest ?: return@LaunchedEffect
                    navController.popBackStack<MainRoute>(inclusive = false)
                    mainViewModel.dispatch(MainMsg.MainNavigationHandled(navigationRequest.token))
                }
                NavHost(navController = navController, startDestination = MainRoute) {
                    composable<MainRoute>(
                        enterTransition = { null },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300),
                            )
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300),
                            )
                        },
                        popExitTransition = { null },
                    ) {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToHistory = { navController.navigate(HistoryRoute) },
                            onNavigateToSettings = { navController.navigate(SettingsRoute) },
                        )
                    }

                    composable<HistoryRoute>(
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300),
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300),
                            )
                        },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300),
                            )
                        },
                    ) {
                        HistoryScreen(onNavigateBack = { navController.popBackStack() })
                    }

                    composable<SettingsRoute>(
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300),
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300),
                            )
                        },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300),
                            )
                        },
                    ) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onBackgroundShareSummaryNotificationsChange =
                            ::handleBackgroundShareSummaryNotificationsToggle,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PENDING_NOTIFICATION_INTENT, pendingEnableBackgroundShareSummaryNotifications)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBackgroundShareResultNotificationIntent(intent)
        consumeSharedAudioIntent(intent)
    }

    private fun consumeBackgroundShareResultNotificationIntent(intent: Intent?) {
        if (intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return
        }
        val jobId = intent.getIntExtra(EXTRA_BACKGROUND_SHARE_RESULT_JOB_ID, -1)
        if (jobId == -1) return

        backgroundShareNotifier.cancelResult(jobId)
        mainViewModel.dispatch(MainMsg.BackgroundShareResultOpened(jobId))
        intent.removeExtra(EXTRA_BACKGROUND_SHARE_RESULT_JOB_ID)
    }

    private fun consumeSharedAudioIntent(intent: Intent?) {
        if (intent == null || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return
        }
        val (sharedUris, replacementIntent) = SharedAudioIntentHandler.consumeSharedUris(intent)
        if (sharedUris.isNotEmpty()) {
            mainViewModel.dispatch(MainMsg.SharedAudioReceived(sharedUris))
        }
        if (replacementIntent !== intent) {
            setIntent(replacementIntent)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleBackgroundShareSummaryNotificationsToggle(enabled: Boolean) {
        if (!enabled) {
            pendingEnableBackgroundShareSummaryNotifications = false
            settingsViewModel.dispatch(SettingsMsg.SetBackgroundShareSummaryNotificationsEnabled(false))
            return
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingEnableBackgroundShareSummaryNotifications = false
            settingsViewModel.dispatch(SettingsMsg.SetBackgroundShareSummaryNotificationsEnabled(true))
            return
        }
        pendingEnableBackgroundShareSummaryNotifications = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val PENDING_NOTIFICATION_INTENT = "pendingBackgroundNotificationPermission"
        const val EXTRA_BACKGROUND_SHARE_RESULT_JOB_ID = "backgroundShareResultJobId"
    }
}
