package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Settings in a private DataStore file, so a test never reads or changes the app's saved settings. */
internal class IsolatedSettings(context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.cacheDir, "test-settings-${System.nanoTime()}.preferences_pb")
    val manager = ApiKeyManager(context, Dispatchers.IO, PreferenceDataStoreFactory.create(scope = scope) { file })

    override fun close() {
        scope.cancel()
        file.delete()
    }
}
