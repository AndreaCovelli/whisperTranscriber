package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
}
