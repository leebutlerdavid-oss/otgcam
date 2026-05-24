package com.otgcam.receiver.model

/**
 * Immutable configuration loaded from encrypted storage.
 */
data class AppConfig(
    val botToken: String,
    val chatId: String,
    val agentId: String,
    val stunServerUrl: String = "stun:stun.l.google.com:19302"
)
