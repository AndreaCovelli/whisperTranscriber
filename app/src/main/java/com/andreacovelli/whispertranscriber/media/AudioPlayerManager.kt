package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioPlayerManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    fun play(uri: Uri) {
        if (_currentUri.value == uri && mediaPlayer != null) {
            mediaPlayer?.start()
            _isPlaying.value = true
            startPositionTracking()
            return
        }

        stop()
        _currentUri.value = uri
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnCompletionListener {
                    stop()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    stop()
                    true
                }
                prepareAsync()
                setOnPreparedListener { mp ->
                    _duration.value = mp.duration
                    mp.start()
                    _isPlaying.value = true
                    startPositionTracking()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerManager", "Error playing audio for URI: $uri", e)
            stop()
        }
    }

    fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            _isPlaying.value = false
            positionJob?.cancel()
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        _position.value = positionMs
    }

    fun stop() {
        positionJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _currentUri.value = null
        _position.value = 0
        _duration.value = 0
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                try {
                    mediaPlayer?.let { _position.value = it.currentPosition }
                } catch (_: IllegalStateException) { }
                delay(200)
            }
        }
    }
}
