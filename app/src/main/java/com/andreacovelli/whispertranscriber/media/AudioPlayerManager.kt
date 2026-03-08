package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioPlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    fun play(uri: Uri) {
        if (_currentUri.value == uri && mediaPlayer != null) {
            mediaPlayer?.start()
            _isPlaying.value = true
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
                setOnPreparedListener { 
                    it.start()
                    _isPlaying.value = true
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
        }
    }

    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _currentUri.value = null
    }
}