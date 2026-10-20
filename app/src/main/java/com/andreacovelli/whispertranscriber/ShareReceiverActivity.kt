package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.andreacovelli.whispertranscriber.ui.main.ShareIntakeState
import com.andreacovelli.whispertranscriber.ui.main.ShareIntakeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    private val intake: ShareIntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intake.accept(intent ?: Intent())
        // Collect while stopped too: staging keeps running after the user leaves (the share's URI
        // grant lives as long as this activity), and the activity must still finish once it is done.
        lifecycleScope.launch {
            intake.state.collect { state ->
                when (state) {
                    ShareIntakeState.Preparing -> Unit
                    ShareIntakeState.Finished -> finish()
                    is ShareIntakeState.ForwardToMain -> {
                        startActivity(
                            Intent(state.intent).apply {
                                setClass(this@ShareReceiverActivity, MainActivity::class.java)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            },
                        )
                        finish()
                    }
                }
            }
        }
    }
}
