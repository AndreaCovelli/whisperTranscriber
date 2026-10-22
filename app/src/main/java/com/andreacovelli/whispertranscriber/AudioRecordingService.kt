package com.andreacovelli.whispertranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.andreacovelli.whispertranscriber.background.createOpenAppPendingIntent
import com.andreacovelli.whispertranscriber.recording.AudioRecordingController
import com.andreacovelli.whispertranscriber.recording.RecordingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps microphone access while [AudioRecordingController] records and saves, then stops itself. */
@AndroidEntryPoint
class AudioRecordingService : Service() {
    @Inject
    lateinit var controller: AudioRecordingController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Start ids begin at 1, so stopping with 0 before the first command arrives is a no-op.
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            controller.state.collect { state ->
                if (state == RecordingState.Idle) stopSelf(latestStartId)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            if (controller.state.value == RecordingState.Idle) stopSelf(startId)
        } else {
            startRecording(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRecording(startId: Int) {
        // startForegroundService() requires startForeground() even when recording cannot begin.
        if (!enterForeground()) {
            controller.abortStart()
            stopSelf(startId)
            return
        }
        if (!controller.openMicrophone()) stopSelf(startId)
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        true
    } catch (_: IllegalStateException) {
        // Includes ForegroundServiceStartNotAllowedException when the app is no longer visible.
        false
    } catch (_: SecurityException) {
        // The microphone permission was revoked between the request and the service start.
        false
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_body))
            .setContentIntent(createOpenAppPendingIntent(this))
            .addAction(R.drawable.ic_stop, getString(R.string.notification_recording_stop), stopIntent)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.andreacovelli.whispertranscriber.action.STOP_RECORDING"

        internal fun startIntent(context: Context): Intent = Intent(context, AudioRecordingService::class.java)
    }
}
