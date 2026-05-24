package com.otgcam.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.jiangdg.usbcamera.UVCCameraHelper
import com.jiangdg.usbcamera.utils.FileUtils
import com.otgcam.agent.model.AppConfig
import com.otgcam.agent.model.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceTextureHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Core foreground service that maintains the UVC camera, processes headset commands,
 * uploads media to Telegram, and manages WebRTC call state with the screen off.
 * Uses AndroidUSBCamera v2.3.8 (stable single-artifact API).
 */
class CameraService : LifecycleService() {

    private var cameraHelper: UVCCameraHelper? = null
    private var textureView: android.view.TextureView? = null
    private var windowManager: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telegramUploader: TelegramUploader? = null
    private var webRtcManager: WebRtcManager? = null
    private var callAutoAcceptor: CallAutoAcceptor? = null
    private var usbMonitor: UsbMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uploadRetryQueue = LinkedList<File>()
    private val inMemoryLog = ArrayDeque<String>(50)
    private var bluetoothHeadsetProxy: BluetoothHeadset? = null
    private var bluetoothStateReceiver: BroadcastReceiver? = null
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_CAPTURE_PHOTO -> capturePhoto()
            ACTION_CAPTURE_VIDEO -> captureVideo(durationSeconds = 30)
            ACTION_TOGGLE_LIVE_AUDIO -> toggleLiveCall()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun handleStart() {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_text)))
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OTGCam::ServiceWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)

        val config = loadConfig() ?: run {
            broadcastLog("Configuration not found. Open app to set up.")
            handleStop()
            return
        }

        telegramUploader = TelegramUploader(config)
        webRtcManager = WebRtcManager(this, config) { candidateJson ->
            serviceScope.launch {
                val signal = com.otgcam.agent.model.CallSignal.fromJson(candidateJson)
                if (signal != null) {
                    telegramUploader?.sendSignal(signal)
                }
            }
        }
        webRtcManager?.initialize()

        callAutoAcceptor = CallAutoAcceptor(telegramUploader!!, webRtcManager!!, this)
        callAutoAcceptor?.startListening()

        usbMonitor = UsbMonitor(this)
        usbMonitor?.start()

        attachHiddenSurface()
        openUVCCamera()

        getSharedPreferences("otgcam_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()

        broadcastStatus("Service started. Waiting for UVC camera.")

        serviceScope.launch {
            drainRetryQueue()
        }

        setupBluetoothHeadset()
    }

    private fun loadConfig(): AppConfig? {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(this)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                this,
                "otgcam_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val token = prefs.getString("bot_token", null) ?: return null
            val chatId = prefs.getString("chat_id", null) ?: return null
            val agentId = prefs.getString("agent_id", null) ?: return null
            AppConfig(token, chatId, agentId)
        } catch (e: Exception) {
            null
        }
    }

    private fun attachHiddenSurface() {
        textureView = android.view.TextureView(this).apply {
            layoutParams = WindowManager.LayoutParams(1, 1)
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager?.addView(textureView, params)
        } catch (e: SecurityException) {
            broadcastLog("Overlay permission not granted. Camera may not render frames.")
            textureView = null
        }
    }

    private fun openUVCCamera() {
        val tv = textureView ?: return
        cameraHelper = UVCCameraHelper.getInstance()
        cameraHelper?.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG)
        cameraHelper?.initUSBMonitor(this, tv, object : UVCCameraHelper.OnMyDevConnectListener {
            override fun onAttachDev(device: UsbDevice?) {
                if (device != null) {
                    VibrationHelper.vibrateUvcEvent(this@CameraService)
                    broadcastStatus("UVC camera attached: ${device.productName}")
                }
            }
            override fun onDettachDev(device: UsbDevice?) {
                if (device != null) {
                    VibrationHelper.vibrateUvcEvent(this@CameraService)
                    broadcastStatus("UVC camera detached: ${device.productName}")
                    cameraHelper?.closeCamera()
                }
            }
            override fun onConnectDev(device: UsbDevice?, isConnected: Boolean) {
                if (isConnected) {
                    broadcastStatus("UVC camera connected")
                    updateNotification("UVC camera connected")
                    // Attach WebRTC video source from TextureView bitmap
                    val eglBase = org.webrtc.EglBase.create()
                    val surfaceTextureHelper = SurfaceTextureHelper.create("UvcThread", eglBase.eglBaseContext)
                    webRtcManager?.attachUvcVideoSource(tv)
                } else {
                    broadcastStatus("UVC camera disconnected")
                    updateNotification("UVC camera disconnected")
                }
            }
            override fun onDisConnectDev(device: UsbDevice?) {
                broadcastStatus("UVC camera disconnected")
                updateNotification("UVC camera disconnected")
            }
        })
        cameraHelper?.registerUSB()
    }

    private fun capturePhoto() {
        val helper = cameraHelper ?: run {
            broadcastLog("Cannot capture: camera not connected")
            return
        }
        if (!helper.isCameraOpened) {
            broadcastLog("Cannot capture: camera not opened")
            return
        }
        val dir = getExternalFilesDir("OTGCam/Photos")
        dir?.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val file = File(dir, "photo_$timestamp.jpg")

        helper.capturePicture(file.absolutePath, object : UVCCameraHelper.OnCaptureListener {
            override fun onCaptureResult(path: String?) {
                VibrationHelper.vibrateCaptureConfirm(this@CameraService)
                val name = File(path ?: return).name
                broadcastLog("Photo captured: $name")
                broadcastStatus("Last capture: $name")
                serviceScope.launch {
                    uploadPhoto(File(path))
                }
            }
        })
    }

    private fun captureVideo(durationSeconds: Int) {
        val helper = cameraHelper ?: run {
            broadcastLog("Cannot capture: camera not connected")
            return
        }
        if (!helper.isCameraOpened) {
            broadcastLog("Cannot capture: camera not opened")
            return
        }
        val dir = getExternalFilesDir("OTGCam/Videos")
        dir?.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "video_$timestamp.mp4")

        helper.startRecording(file.absolutePath, object : UVCCameraHelper.OnEncodeResultListener {
            override fun onEncodeResult(data: ByteArray?, offset: Int, length: Int, timestamp: Long, type: Int) {}
            override fun onRecordResult(path: String?) {
                val name = File(path ?: return).name
                broadcastLog("Video recorded: $name")
                broadcastStatus("Last capture: $name")
                serviceScope.launch {
                    uploadVideo(File(path))
                }
            }
        })

        VibrationHelper.vibrateCaptureConfirm(this@CameraService)
        broadcastLog("Recording video for ${durationSeconds}s...")

        Handler(Looper.getMainLooper()).postDelayed({
            helper.stopRecording()
        }, durationSeconds * 1000L)
    }

    private suspend fun uploadPhoto(file: File) {
        val uploader = telegramUploader ?: run {
            uploadRetryQueue.add(file)
            return
        }
        try {
            val result = uploader.uploadPhoto(file)
            when (result) {
                is UploadResult.Success -> {
                    VibrationHelper.vibrateUploadSuccess(this@CameraService)
                    broadcastLog("Uploaded: ${file.name}")
                    broadcastStatus("Last upload: ${file.name}")
                    drainRetryQueue()
                }
                is UploadResult.Failure -> {
                    broadcastLog("Upload failed, queued for retry: ${result.reason}")
                    uploadRetryQueue.add(file)
                }
            }
        } catch (e: Exception) {
            broadcastLog("Upload exception: ${e.message}")
            uploadRetryQueue.add(file)
        }
    }

    private suspend fun uploadVideo(file: File) {
        val uploader = telegramUploader ?: run {
            uploadRetryQueue.add(file)
            return
        }
        try {
            val result = uploader.uploadVideo(file)
            when (result) {
                is UploadResult.Success -> {
                    VibrationHelper.vibrateUploadSuccess(this@CameraService)
                    broadcastLog("Uploaded: ${file.name}")
                    broadcastStatus("Last upload: ${file.name}")
                    drainRetryQueue()
                }
                is UploadResult.Failure -> {
                    broadcastLog("Upload failed, queued for retry: ${result.reason}")
                    uploadRetryQueue.add(file)
                }
            }
        } catch (e: Exception) {
            broadcastLog("Upload exception: ${e.message}")
            uploadRetryQueue.add(file)
        }
    }

    private suspend fun drainRetryQueue() {
        val uploader = telegramUploader ?: return
        while (uploadRetryQueue.isNotEmpty()) {
            val file = uploadRetryQueue.peek() ?: break
            val result = try {
                when {
                    file.extension.equals("jpg", true) -> uploader.uploadPhoto(file)
                    file.extension.equals("mp4", true) -> uploader.uploadVideo(file)
                    else -> UploadResult.Failure("Unknown file type", file)
                }
            } catch (e: Exception) {
                UploadResult.Failure("Exception: ${e.message}", file)
            }
            when (result) {
                is UploadResult.Success -> {
                    uploadRetryQueue.poll()
                    broadcastLog("Retry upload succeeded: ${file.name}")
                }
                is UploadResult.Failure -> {
                    broadcastLog("Retry upload failed: ${result.reason}")
                    break
                }
            }
        }
    }

    private fun toggleLiveCall() {
        val manager = webRtcManager ?: return
        if (manager.isCallActive) {
            manager.endCall()
            broadcastLog("Live call ended")
            restoreAudioMode()
        } else {
            manager.startOutgoingCall { offerJson ->
                serviceScope.launch {
                    val signal = com.otgcam.agent.model.CallSignal.fromJson(offerJson)
                    if (signal != null) {
                        telegramUploader?.sendSignal(signal)
                    }
                    broadcastLog("Live call started")
                }
            }
            if (bluetoothHeadsetProxy?.connectedDevices?.isNotEmpty() == true) {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    private fun setupBluetoothHeadset() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadsetProxy = proxy as BluetoothHeadset
                    bluetoothStateReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                            if (state == BluetoothHeadset.STATE_CONNECTED) {
                                audioManager?.startBluetoothSco()
                                audioManager?.isBluetoothScoOn = true
                            } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                                audioManager?.stopBluetoothSco()
                                audioManager?.isBluetoothScoOn = false
                            }
                        }
                    }
                    registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                bluetoothHeadsetProxy = null
            }
        }, BluetoothProfile.HEADSET)
    }

    private fun restoreAudioMode() {
        audioManager?.mode = AudioManager.MODE_NORMAL
    }

    private fun handleStop() {
        callAutoAcceptor?.stopListening()
        webRtcManager?.endCall()
        restoreAudioMode()
        cameraHelper?.unregisterUSB()
        cameraHelper?.release()
        cameraHelper = null
        textureView?.let { windowManager?.removeView(it) }
        textureView = null
        windowManager = null
        usbMonitor?.stop()
        serviceScope.cancel()
        wakeLock?.release()
        wakeLock = null
        getSharedPreferences("otgcam_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val captureIntent = Intent(this, CameraService::class.java).apply {
            action = ACTION_CAPTURE_PHOTO
        }
        val capturePendingIntent = PendingIntent.getService(
            this, 0, captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_camera, getString(R.string.action_capture_photo), capturePendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun broadcastStatus(message: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        broadcastLog(message)
    }

    private fun broadcastLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        inMemoryLog.add(entry)
        if (inMemoryLog.size > 50) {
            inMemoryLog.removeFirst()
        }
        val intent = Intent(ACTION_LOG_UPDATE).apply {
            putExtra(EXTRA_LOG_MESSAGE, entry)
            putStringArrayListExtra(EXTRA_LOG_ALL, ArrayList(inMemoryLog))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.otgcam.agent.ACTION_START"
        const val ACTION_STOP = "com.otgcam.agent.ACTION_STOP"
        const val ACTION_CAPTURE_PHOTO = "com.otgcam.agent.ACTION_CAPTURE_PHOTO"
        const val ACTION_CAPTURE_VIDEO = "com.otgcam.agent.ACTION_CAPTURE_VIDEO"
        const val ACTION_TOGGLE_LIVE_AUDIO = "com.otgcam.agent.ACTION_TOGGLE_LIVE_AUDIO"
        const val ACTION_STATUS_UPDATE = "com.otgcam.agent.ACTION_STATUS_UPDATE"
        const val ACTION_LOG_UPDATE = "com.otgcam.agent.ACTION_LOG_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
        const val EXTRA_LOG_ALL = "extra_log_all"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "otgcam_channel"
    }
}
