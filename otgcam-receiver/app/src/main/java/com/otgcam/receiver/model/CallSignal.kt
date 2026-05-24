package com.otgcam.receiver.model

import org.json.JSONObject

/**
 * Data class representing a WebRTC signaling message exchanged via Telegram.
 */
data class CallSignal(
    val event: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val videoEnabled: Boolean = false,
    val agentId: String = ""
) {
    companion object {
        /**
         * Deserialize a CallSignal from a JSON string.
         * @param json Raw JSON string.
         * @return Parsed [CallSignal] or null if parsing fails.
         */
        fun fromJson(json: String): CallSignal? {
            return try {
                val obj = JSONObject(json)
                CallSignal(
                    event = obj.getString("event"),
                    sdp = if (obj.has("sdp") && !obj.isNull("sdp")) obj.getString("sdp") else null,
                    candidate = if (obj.has("candidate") && !obj.isNull("candidate")) obj.getString("candidate") else null,
                    videoEnabled = obj.optBoolean("videoEnabled", false),
                    agentId = obj.optString("agentId", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Serialize this signal to a JSON string.
     * @return Compact JSON representation.
     */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("event", event)
        sdp?.let { obj.put("sdp", it) }
        candidate?.let { obj.put("candidate", it) }
        obj.put("videoEnabled", videoEnabled)
        obj.put("agentId", agentId)
        return obj.toString()
    }
}
