package com.otgcam.receiver

import android.content.Context
import com.otgcam.receiver.model.AppConfig
import com.otgcam.receiver.model.CallSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Polls the Telegram Bot API for new media and signaling messages
 * directed at the configured agent.
 */
class TelegramPoller(private val config: AppConfig, private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.telegram.org/bot${config.botToken}"
    private var pollingJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Begin long-polling Telegram for updates.
     * @param onPhoto Callback when a photo is received.
     * @param onVideo Callback when a video is received.
     * @param onSignal Callback when a signaling message is received.
     */
    fun startPolling(
        onPhoto: (File) -> Unit,
        onVideo: (File) -> Unit,
        onSignal: (CallSignal) -> Unit
    ) {
        pollingJob = scope.launch {
            val prefs = context.getSharedPreferences("otgcam_receiver_prefs", Context.MODE_PRIVATE)
            var lastUpdateId = prefs.getLong("last_update_id", 0L)
            while (isActive) {
                val url = "$baseUrl/getUpdates?offset=${lastUpdateId + 1}&timeout=30&allowed_updates=%5B%22message%22%5D"
                val request = Request.Builder().url(url).build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            delay(5000)
                            return@use
                        }
                        val body = response.body?.string() ?: return@use
                        val root = JSONObject(body)
                        if (!root.optBoolean("ok", false)) {
                            delay(5000)
                            return@use
                        }
                        val result = root.optJSONArray("result") ?: return@use
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            val updateId = update.getLong("update_id")
                            if (updateId > lastUpdateId) {
                                lastUpdateId = updateId
                                prefs.edit().putLong("last_update_id", lastUpdateId).apply()
                            }
                            val message = update.optJSONObject("message") ?: continue
                            val photoArray = message.optJSONArray("photo")
                            if (photoArray != null && photoArray.length() > 0) {
                                val largest = photoArray.getJSONObject(photoArray.length() - 1)
                                val fileId = largest.getString("file_id")
                                val dir = File(context.filesDir, "photos").apply { mkdirs() }
                                val dest = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                                val downloaded = downloadFile(fileId, dest)
                                onPhoto(downloaded)
                            }
                            val videoObj = message.optJSONObject("video")
                            if (videoObj != null) {
                                val fileId = videoObj.getString("file_id")
                                val dir = File(context.filesDir, "videos").apply { mkdirs() }
                                val dest = File(dir, "video_${System.currentTimeMillis()}.mp4")
                                val downloaded = downloadFile(fileId, dest)
                                onVideo(downloaded)
                            }
                            val text = message.optString("text", "")
                            if (text.startsWith("{")) {
                                val signal = CallSignal.fromJson(text)
                                if (signal != null && signal.agentId == config.agentId) {
                                    onSignal(signal)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    delay(5000)
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    /**
     * Stop the polling coroutine.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Download a file from Telegram by file_id.
     * @param fileId Telegram file identifier.
     * @param destination Local file to write.
     * @return The downloaded [File].
     */
    private suspend fun downloadFile(fileId: String, destination: File): File = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/getFile?file_id=$fileId")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty body")
            val root = JSONObject(body)
            if (!root.optBoolean("ok", false)) throw IOException("API error")
            val result = root.getJSONObject("result")
            val filePath = result.getString("file_path")
            val downloadUrl = "https://api.telegram.org/file/bot${config.botToken}/$filePath"
            val downloadRequest = Request.Builder().url(downloadUrl).build()
            client.newCall(downloadRequest).execute().use { downloadResponse ->
                if (!downloadResponse.isSuccessful) throw IOException("Download HTTP ${downloadResponse.code}")
                val bytes = downloadResponse.body?.bytes() ?: throw IOException("Empty download body")
                destination.writeBytes(bytes)
            }
        }
        destination
    }

    /**
     * Send a signaling message to the Agent via Telegram.
     * @param signal The signaling payload.
     * @return True if Telegram reports ok=true.
     */
    suspend fun sendSignal(signal: CallSignal): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("chat_id", config.chatId)
            .add("text", signal.toJson())
            .build()
        val request = Request.Builder()
            .url("$baseUrl/sendMessage")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val json = response.body?.string() ?: return@withContext false
                return@withContext JSONObject(json).optBoolean("ok", false)
            }
        } catch (e: IOException) {
            return@withContext false
        }
    }
}
