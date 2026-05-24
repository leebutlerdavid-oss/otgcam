package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.otgcam.agent.databinding.ActivityMainBinding
import com.otgcam.agent.ui.LogAdapter
import com.otgcam.agent.ui.SetupFragment

/**
 * Primary activity for the OTGCam Agent application.
 * Handles first-time setup, permission acquisition, service control,
 * and real-time status/log display.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CameraService.ACTION_STATUS_UPDATE -> {
                    val message = intent.getStringExtra(CameraService.EXTRA_STATUS_MESSAGE) ?: return
                    updateStatusCard(message)
                }
                CameraService.ACTION_LOG_UPDATE -> {
                    val message = intent.getStringExtra(CameraService.EXTRA_LOG_MESSAGE) ?: return
                    logAdapter.addEntry(message)
                    binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!isSetupComplete()) {
            showSetupFragment()
            return
        }

        setupMainUi()
        requestAllPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val componentName = ComponentName(this, HeadsetReceiver::class.java)
        audioManager.registerMediaButtonEventReceiver(componentName)
    }

    private fun isSetupComplete(): Boolean {
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
            prefs.getBoolean("setup_complete", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun showSetupFragment() {
        val fragment = SetupFragment.newInstance()
        fragment.onSetupComplete = {
            supportFragmentManager.beginTransaction().remove(fragment).commit()
            recreate()
        }
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commit()
    }

    private fun setupMainUi() {
        logAdapter = LogAdapter()
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter

        val prefs = getSharedPreferences("otgcam_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("service_running", false)
        updateButtonStates(running)

        binding.btnStartService.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
            )
            updateButtonStates(true)
        }

        binding.btnStopService.setOnClickListener {
            startService(
                Intent(this, CameraService::class.java).apply {
                    action = CameraService.ACTION_STOP
                }
            )
            updateButtonStates(false)
        }

        binding.btnClearLog.setOnClickListener {
            logAdapter.clear()
        }
    }

    private fun updateButtonStates(running: Boolean) {
        binding.btnStartService.isEnabled = !running
        binding.btnStopService.isEnabled = running
        binding.tvServiceStatus.text = if (running) getString(R.string.running) else getString(R.string.stopped)
    }

    private fun updateStatusCard(message: String) {
        when {
            message.contains("UVC camera connected") -> {
                binding.tvUvcStatus.text = getString(R.string.connected)
                binding.tvUvcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            }
            message.contains("UVC camera disconnected") -> {
                binding.tvUvcStatus.text = getString(R.string.disconnected)
                binding.tvUvcStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            }
            message.contains("Service started") -> {
                binding.tvServiceStatus.text = getString(R.string.running)
            }
        }
        if (message.contains("capture")) {
            binding.tvLastCapture.text = message.substringAfterLast(": ", message)
        }
        if (message.contains("Upload") || message.contains("upload")) {
            binding.tvLastUpload.text = message.substringAfterLast(": ", message)
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList()).filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                val builder = AlertDialog.Builder(this)
                builder.setTitle(R.string.permission_dialog_title)
                builder.setMessage(R.string.permission_dialog_message)
                builder.setCancelable(false)
                builder.setPositiveButton(R.string.retry) { _, _ -> requestAllPermissions() }
                builder.show()
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(CameraService.ACTION_STATUS_UPDATE)
            addAction(CameraService.ACTION_LOG_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
        val prefs = getSharedPreferences("otgcam_prefs", Context.MODE_PRIVATE)
        updateButtonStates(prefs.getBoolean("service_running", false))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val componentName = ComponentName(this, HeadsetReceiver::class.java)
        audioManager.unregisterMediaButtonEventReceiver(componentName)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
