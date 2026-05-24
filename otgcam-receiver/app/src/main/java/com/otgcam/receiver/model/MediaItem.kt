package com.otgcam.receiver.model

import org.json.JSONObject

/**
 * Sealed class representing media items received from the Agent.
 */
sealed class MediaItem {
    /**
     * Photo item.
     * @property localPath Absolute path to the downloaded JPEG.
     * @property timestamp Unix epoch milliseconds when received.
     * @property fileSizeBytes File size on disk.
     */
    data class Photo(
        val localPath: String,
        val timestamp: Long,
        val fileSizeBytes: Long
    ) : MediaItem()

    /**
     * Video item.
     * @property localPath Absolute path to the downloaded MP4.
     * @property timestamp Unix epoch milliseconds when received.
     * @property durationMs Video duration in milliseconds.
     * @property fileSizeBytes File size on disk.
     */
    data class Video(
        val localPath: String,
        val timestamp: Long,
        val durationMs: Long,
        val fileSizeBytes: Long
    ) : MediaItem()

    companion object {
        /**
         * Serialize a list of media items to a JSON array string.
         */
        fun listToJson(items: List<MediaItem>): String {
            val array = org.json.JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                when (item) {
                    is Photo -> {
                        obj.put("type", "photo")
                        obj.put("path", item.localPath)
                        obj.put("timestamp", item.timestamp)
                        obj.put("size", item.fileSizeBytes)
                    }
                    is Video -> {
                        obj.put("type", "video")
                        obj.put("path", item.localPath)
                        obj.put("timestamp", item.timestamp)
                        obj.put("duration", item.durationMs)
                        obj.put("size", item.fileSizeBytes)
                    }
                }
                array.put(obj)
            }
            return array.toString()
        }

        /**
         * Deserialize a JSON array string to a list of media items.
         */
        fun listFromJson(json: String): List<MediaItem> {
            val items = mutableListOf<MediaItem>()
            try {
                val array = org.json.JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val type = obj.getString("type")
                    val path = obj.getString("path")
                    val timestamp = obj.getLong("timestamp")
                    val size = obj.getLong("size")
                    when (type) {
                        "photo" -> items.add(Photo(path, timestamp, size))
                        "video" -> {
                            val duration = obj.getLong("duration")
                            items.add(Video(path, timestamp, duration, size))
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore malformed JSON.
            }
            return items
        }
    }
}
