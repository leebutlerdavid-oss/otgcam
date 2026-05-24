package com.otgcam.receiver

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.otgcam.receiver.databinding.ActivityMediaFeedBinding
import com.otgcam.receiver.model.AppConfig
import com.otgcam.receiver.model.MediaItem
import com.otgcam.receiver.ui.MediaAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Primary activity displaying the live scrolling media feed received from the Agent.
 * Provides controls to initiate audio or video calls.
 */
class MediaFeedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaFeedBinding
    private lateinit var adapter: MediaAdapter
    private lateinit var poller: TelegramPoller
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = loadConfig() ?: run {
            Toast.makeText(this, "Configuration missing. Please re-launch app.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        poller = TelegramPoller(config, this)

        adapter = MediaAdapter()
        binding.rvMedia.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, true)
        binding.rvMedia.adapter = adapter

        restorePersistedItems()

        binding.btnAudioCall.setOnClickListener {
            CallManager.initiateCall(videoEnabled = false, this)
        }

        binding.btnVideoCall.setOnClickListener {
            CallManager.initiateCall(videoEnabled = true, this)
        }
    }

    private fun loadConfig(): AppConfig? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                this,
                "otgcam_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val token = prefs.getString("bot_token", null) ?: return null
            val chatId = prefs.getString("chat_id", null) ?: return null
            val agentId = prefs.getString("agent_id", null) ?: return null
            AppConfig(token, chatId, agentId)
        } catch (e: Exception) {
            null
        }
    }

    private fun restorePersistedItems() {
        try {
            val file = File(filesDir, "media_feed.json")
            if (file.exists()) {
                val json = file.readText()
                val items = MediaItem.listFromJson(json)
                adapter.setItems(items)
            }
        } catch (e: Exception) {
            // Ignore restore errors.
        }
    }

    override fun onResume() {
        super.onResume()
        binding.chipStatus.text = getString(R.string.status_polling)
        binding.chipStatus.setChipBackgroundColorResource(R.color.status_polling)
        scope.launch {
            poller.startPolling(
                onPhoto = { file ->
                    val item = MediaItem.Photo(
                        localPath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        fileSizeBytes = file.length()
                    )
                    runOnUiThread {
                        adapter.insertItem(item)
                        binding.rvMedia.scrollToPosition(0)
                        binding.chipStatus.text = getString(R.string.status_connected)
                        binding.chipStatus.setChipBackgroundColorResource(R.color.status_connected)
                        persistItem(item)
                    }
                },
                onVideo = { file ->
                    val duration = getVideoDuration(file)
                    val item = MediaItem.Video(
                        localPath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        durationMs = duration,
                        fileSizeBytes = file.length()
                    )
                    runOnUiThread {
                        adapter.insertItem(item)
                        binding.rvMedia.scrollToPosition(0)
                        binding.chipStatus.text = getString(R.string.status_connected)
                        binding.chipStatus.setChipBackgroundColorResource(R.color.status_connected)
                        persistItem(item)
                    }
                },
                onSignal = { signal ->
                    // Signals are handled by LiveCallActivity if active.
                }
            )
        }
    }

    override fun onPause() {
        super.onPause()
        poller.stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun persistItem(item: MediaItem) {
        try {
            val file = File(filesDir, "media_feed.json")
            val existing = if (file.exists()) MediaItem.listFromJson(file.readText()) else emptyList()
            val updated = mutableListOf(item)
            updated.addAll(existing)
            if (updated.size > 200) {
                updated.subList(200, updated.size).clear()
            }
            file.writeText(MediaItem.listToJson(updated))
        } catch (e: Exception) {
            // Ignore persist errors.
        }
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }
}
