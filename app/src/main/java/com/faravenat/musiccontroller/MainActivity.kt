package com.faravenat.musiccontroller

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.faravenat.musiccontroller.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    // Media
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

    // Cámara
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraActive = false

    // GPS
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { location ->
        if (location.hasSpeed()) {
            val kmh = location.speed * 3.6f
            runOnUiThread {
                binding.tvSpeed.text = "🚴 ${"%.1f".format(kmh)} km/h"
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupVolumeControls()
        setupMediaButtons()
        setupCameraButton()
        requestNeededPermissions()
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
        startGps()
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
        disconnectSessions()
        stopCamera()
        stopGps()
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty())
            requestPermissions(needed.toTypedArray(), PERMISSIONS_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            grantResults.forEachIndexed { i, result ->
                if (result == PackageManager.PERMISSION_GRANTED) {
                    when (permissions[i]) {
                        Manifest.permission.ACCESS_FINE_LOCATION -> startGps()
                    }
                }
            }
        }
    }

    // --- Cámara ---

    private fun setupCameraButton() {
        binding.btnCamera.setOnClickListener {
            if (cameraActive) stopCamera() else startCamera()
        }
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                cameraActive = true
                binding.cameraPreview.visibility = android.view.View.VISIBLE
                binding.btnCamera.setImageResource(R.drawable.ic_camera_off)
            } catch (e: Exception) { /* cámara no disponible */ }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraActive = false
        binding.cameraPreview.visibility = android.view.View.INVISIBLE
        binding.btnCamera.setImageResource(R.drawable.ic_camera_on)
    }

    // --- GPS ---

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
    }

    private fun stopGps() {
        locationManager?.removeUpdates(locationListener)
    }

    // --- Volumen ---

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

    // --- Media ---

    private fun setupMediaButtons() {
        binding.btnPrevious.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        binding.btnNext.setOnClickListener { activeController?.transportControls?.skipToNext() }
        binding.btnPlayPause.setOnClickListener {
            val state = activeController?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) activeController?.transportControls?.pause()
            else activeController?.transportControls?.play()
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
        if (controller == null) { showNoMusicState(); return }

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
        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        binding.progressBar.progress = 0
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
    }

    private fun updateMetadataUI(metadata: MediaMetadata?) {
        binding.tvSongTitle.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Desconocido"
        binding.tvArtist.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        binding.progressBar.max = if (duration > 0) (duration / 1000).toInt() else 100
        binding.tvTotalTime.text = formatTime(duration)
    }

    private fun updatePlaybackUI(state: PlaybackState?) {
        if (state?.state == PlaybackState.STATE_PLAYING)
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        else
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    private fun updateProgress() {
        val state = activeController?.playbackState ?: return
        val positionMs = if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = System.currentTimeMillis() - state.lastPositionUpdateTime
            state.position + (elapsed * state.playbackSpeed).toLong()
        } else state.position
        binding.progressBar.progress = (positionMs / 1000).toInt()
        binding.tvCurrentTime.text = formatTime(positionMs)
    }

    private fun syncVolumeSlider() {
        binding.volumeSlider.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
