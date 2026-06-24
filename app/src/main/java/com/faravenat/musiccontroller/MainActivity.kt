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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import android.view.View
import android.widget.Toast
import com.faravenat.musiccontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager

    // Media
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            binding.tvTime.text = "🕐 ${timeFormat.format(Date())}"
            progressHandler.postDelayed(this, 1000)
        }
    }

    // Cámara IP WiFi
    private var exoPlayer: ExoPlayer? = null
    private var cameraActive = false

    companion object {
        private const val CAMERA_IP = "10.165.35.30"
    }

    // GPS
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { location ->
        if (location.hasSpeed()) {
            val kmh = location.speed * 3.6f
            runOnUiThread { binding.tvSpeed.text = "🚴 ${"%.1f".format(kmh)} km/h" }
        }
    }

    // Health Connect
    private var healthClient: HealthConnectClient? = null
    private val heartRateHandler = Handler(Looper.getMainLooper())
    private val heartRateRunnable = object : Runnable {
        override fun run() {
            lifecycleScope.launch { fetchHeartRate() }
            heartRateHandler.postDelayed(this, 30_000)
        }
    }
    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )
    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { lifecycleScope.launch { fetchHeartRate() } }

    private val permissionsRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupVolumeControls()
        setupMediaButtons()
        setupCameraButton()
        setupHealthConnect()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        syncVolumeSlider()
        if (hasNotificationAccess()) connectToMediaSessions() else showPermissionDialog()
        progressHandler.post(progressRunnable)
        startGps()
        heartRateHandler.post(heartRateRunnable)
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
        heartRateHandler.removeCallbacks(heartRateRunnable)
        disconnectSessions()
        stopCamera()
        stopGps()
    }

    // --- Fullscreen y pantalla encendida ---

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // --- Permisos cámara y GPS ---

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), permissionsRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionsRequestCode) {
            grantResults.forEachIndexed { i, result ->
                if (result == PackageManager.PERMISSION_GRANTED && permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION)
                    startGps()
            }
        }
    }

    // --- Health Connect (pulso Mi Band 8) ---

    private fun setupHealthConnect() {
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            healthClient = HealthConnectClient.getOrCreate(this)
            lifecycleScope.launch { checkHealthPermissions() }
        }
    }

    private suspend fun checkHealthPermissions() {
        val granted = healthClient?.permissionController?.getGrantedPermissions() ?: emptySet()
        if (!granted.containsAll(healthPermissions)) {
            healthPermissionLauncher.launch(healthPermissions)
        } else {
            fetchHeartRate()
        }
    }

    private suspend fun fetchHeartRate() {
        val client = healthClient ?: return
        try {
            val now = Instant.now()
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(7200), now)
                )
            )
            val bpm = response.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute
            withContext(Dispatchers.Main) {
                binding.tvHeartRate.text = if (bpm != null) "❤️ $bpm bpm" else "❤️ -- bpm"
            }
        } catch (e: SecurityException) {
            withContext(Dispatchers.Main) { binding.tvHeartRate.text = "❤️ sin permiso" }
            lifecycleScope.launch { checkHealthPermissions() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { binding.tvHeartRate.text = "❤️ ?? bpm" }
        }
    }

    // --- Cámara IP WiFi (RTSP) ---

    private fun setupCameraButton() {
        binding.btnCamera.setOnClickListener {
            if (cameraActive) stopCamera() else {
                lifecycleScope.launch { scanAndConnect() }
            }
        }
    }

    private suspend fun scanAndConnect() {
        val ports = listOf(554, 8554, 80, 8080, 8000, 1935, 9000, 443)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Escaneando cámara...", Toast.LENGTH_SHORT).show()
        }
        val open = ports.map { port ->
            kotlinx.coroutines.coroutineScope {
                async(Dispatchers.IO) {
                    try {
                        Socket().use { it.connect(InetSocketAddress(CAMERA_IP, port), 1500); port }
                    } catch (e: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()

        withContext(Dispatchers.Main) {
            if (open.isEmpty()) {
                Toast.makeText(this@MainActivity, "Cámara no responde en ningún puerto", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Puertos abiertos: ${open.joinToString()}", Toast.LENGTH_LONG).show()
                when {
                    554 in open  -> startCamera()
                    8554 in open -> startCameraPort(8554)
                    80 in open   -> startCameraMjpeg(80)
                    8080 in open -> startCameraMjpeg(8080)
                }
            }
        }
    }

    private fun startCameraPort(port: Int) = startRtsp("rtsp://$CAMERA_IP:$port/")
    private fun startCameraMjpeg(port: Int) = startRtsp("http://$CAMERA_IP:$port/stream")

    private fun startCamera() = startRtsp("rtsp://$CAMERA_IP:554/")

    private fun startRtsp(url: String) {
        val silentAudio = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(silentAudio, false)
            .build()
            .also { exoPlayer = it }
        player.volume = 0f
        player.setVideoSurfaceView(binding.cameraPreview)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    cameraActive = true
                    binding.cameraPreview.visibility = View.VISIBLE
                    binding.btnCamera.setImageResource(R.drawable.ic_camera_off)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                stopCamera()
            }
        })
        val source = RtspMediaSource.Factory()
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        binding.btnCamera.setImageResource(R.drawable.ic_camera_off)
    }

    private fun stopCamera() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        cameraActive = false
        binding.cameraPreview.visibility = View.GONE
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
        binding.volumeSlider.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
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
            .setMessage("Para controlar la música necesita acceso a las notificaciones.\n\nToca Abrir, activa la app en la lista y vuelve.")
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
        } catch (e: SecurityException) { showPermissionDialog() }
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
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
