package com.faravenat.musiccontroller

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.faravenat.musiccontroller.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupVolumeControls()
        setupMediaButtons()
    }

    override fun onResume() {
        super.onResume()
        syncVolumeSlider()
        if (hasNotificationAccess()) {
            connectToMediaSessions()
        } else {
            showPermissionDialog()
        }
        progressHandler.post(progressRunnable)
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
        disconnectSessions()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupVolumeControls() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.max = max
        syncVolumeSlider()

        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnVolumeDown.setOnClickListener {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            syncVolumeSlider()
        }
        binding.btnVolumeUp.setOnClickListener {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            syncVolumeSlider()
        }
    }

    private fun setupMediaButtons() {
        binding.btnPrevious.setOnClickListener {
            activeController?.transportControls?.skipToPrevious()
        }
        binding.btnNext.setOnClickListener {
            activeController?.transportControls?.skipToNext()
        }
        binding.btnPlayPause.setOnClickListener {
            val state = activeController?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                activeController?.transportControls?.pause()
            } else {
                activeController?.transportControls?.play()
            }
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val cn = ComponentName(this, MediaService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(cn)
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage("Para controlar la música, esta app necesita acceso a las notificaciones.\n\nToca Abrir, activa la app en la lista y vuelve.")
            .setPositiveButton("Abrir") { _, _ ->
                startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setCancelable(false)
            .show()
    }

    private fun connectToMediaSessions() {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionManager = mgr
        val cn = ComponentName(this, MediaService::class.java)

        try {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                setController(controllers?.firstOrNull())
            }
            sessionListener = listener
            mgr.addOnActiveSessionsChangedListener(listener, cn)
            setController(mgr.getActiveSessions(cn).firstOrNull())
        } catch (e: SecurityException) {
            showPermissionDialog()
        }
    }

    private fun disconnectSessions() {
        sessionListener?.let { mediaSessionManager?.removeOnActiveSessionsChangedListener(it) }
        controllerCallback?.let { activeController?.unregisterCallback(it) }
        activeController = null
    }

    private fun setController(controller: MediaController?) {
        controllerCallback?.let { activeController?.unregisterCallback(it) }
        activeController = controller

        if (controller == null) {
            showNoMusicState()
            return
        }

        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = updateMetadataUI(metadata)
            override fun onPlaybackStateChanged(state: PlaybackState?) = updatePlaybackUI(state)
        }
        controllerCallback = cb
        controller.registerCallback(cb)

        updateMetadataUI(controller.metadata)
        updatePlaybackUI(controller.playbackState)
    }

    private fun showNoMusicState() {
        binding.tvSongTitle.text = "Sin música activa"
        binding.tvArtist.text = "Abre Spotify, YouTube Music u otra app"
        binding.ivArtwork.setImageResource(R.drawable.ic_music_note)
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        binding.progressBar.progress = 0
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
    }

    private fun updateMetadataUI(metadata: MediaMetadata?) {
        binding.tvSongTitle.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Desconocido"
        binding.tvArtist.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (artwork != null) {
            binding.ivArtwork.setImageBitmap(artwork)
        } else {
            binding.ivArtwork.setImageResource(R.drawable.ic_music_note)
        }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        binding.progressBar.max = if (duration > 0) (duration / 1000).toInt() else 100
        binding.tvTotalTime.text = formatTime(duration)
    }

    private fun updatePlaybackUI(state: PlaybackState?) {
        if (state?.state == PlaybackState.STATE_PLAYING) {
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        }
    }

    private fun updateProgress() {
        val state = activeController?.playbackState ?: return
        val positionMs = if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = System.currentTimeMillis() - state.lastPositionUpdateTime
            state.position + (elapsed * state.playbackSpeed).toLong()
        } else {
            state.position
        }
        val positionSec = (positionMs / 1000).toInt()
        binding.progressBar.progress = positionSec
        binding.tvCurrentTime.text = formatTime(positionMs)
    }

    private fun syncVolumeSlider() {
        binding.volumeSlider.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
