package com.otgcam.agent

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import com.otgcam.agent.model.AppConfig
import com.otgcam.agent.model.CallSignal
import com.otgcam.agent.model.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles all Telegram Bot API communication for the Agent app.
 * Uploads photos and videos, sends text messages and signaling data,
 * and polls for incoming call signals.
 */
class TelegramUploader(private val config: AppConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.telegram.org/bot${config.botToken}"

    /**
     * Upload a photo to Telegram via sendPhoto.
     * @param file JPEG image file.
     * @return [UploadResult] indicating success or failure.
     */
    suspend fun uploadPhoto(file: File): UploadResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", config.chatId)
            .addFormDataPart("caption", "Photo captured at $timestamp")
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/sendPhoto")
            .post(requestBody)
            .build()
        executeUpload(request, file)
    }

    /**
     * Upload a video to Telegram via sendVideo.
     * @param file MP4 video file.
     * @return [UploadResult] indicating success or failure.
     */
    suspend fun uploadVideo(file: File): UploadResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val duration = getVideoDurationSeconds(file)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", config.chatId)
            .addFormDataPart("caption", "Video captured at $timestamp")
            .addFormDataPart("duration", duration.toString())
            .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/sendVideo")
            .post(requestBody)
            .build()
        executeUpload(request, file)
    }

    /**
     * Send a plain text message to the configured chat.
     * @param text Message body.
     * @return True if Telegram reports ok=true.
     */
    suspend fun sendTextMessage(text: String): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("chat_id", config.chatId)
            .add("text", text)
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

    /**
     * Serialize a [CallSignal] and send it as a text message.
     * @param signal Signaling payload.
     * @return True if Telegram reports ok=true.
     */
    suspend fun sendSignal(signal: CallSignal): Boolean {
        return sendTextMessage(signal.toJson())
    }

    /**
     * Long-poll Telegram getUpdates for incoming signaling messages.
     * Only processes JSON messages whose agentId matches [config.agentId].
     * @param onSignal Callback invoked for each matching signal.
     */
    suspend fun pollForSignals(onSignal: (CallSignal) -> Unit) = withContext(Dispatchers.IO) {
        var lastUpdateId = 0L
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
                        }
                        val message = update.optJSONObject("message") ?: continue
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

    private suspend fun executeUpload(request: Request, file: File): UploadResult {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return UploadResult.Failure("HTTP ${response.code}", file)
                }
                val body = response.body?.string() ?: return UploadResult.Failure("Empty body", file)
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    val description = json.optJSONObject("error_description")?.toString()
                        ?: json.optString("description", "Unknown error")
                    return UploadResult.Failure(description, file)
                }
                val result = json.getJSONObject("result")
                val fileId = result.optString("file_id", "")
                return UploadResult.Success(fileId, file.name)
            }
        } catch (e: IOException) {
            delay(3000)
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return UploadResult.Failure("Retry HTTP ${response.code}", file)
                    }
                    val body = response.body?.string() ?: return UploadResult.Failure("Retry empty body", file)
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        return UploadResult.Failure("Retry failed", file)
                    }
                    val result = json.getJSONObject("result")
                    val fileId = result.optString("file_id", "")
                    return UploadResult.Success(fileId, file.name)
                }
            } catch (e2: IOException) {
                return UploadResult.Failure("IOException after retry: ${e2.message}", file)
            }
        } catch (e: Exception) {
            return UploadResult.Failure("Exception: ${e.message}", file)
        }
    }

    private fun getVideoDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (durationStr?.toLongOrNull() ?: 0L).toInt() / 1000
        } catch (e: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors.
            }
        }
    }
}
