package com.otgcam.agent.model

/**
 * Sealed class representing the result of a Telegram media upload.
 */
sealed class UploadResult {
    /**
     * Upload succeeded.
     * @property fileId Telegram file identifier.
     * @property fileName Original file name.
     */
    data class Success(val fileId: String, val fileName: String) : UploadResult()

    /**
     * Upload failed.
     * @property reason Human-readable failure description.
     * @property file The local file that failed to upload.
     */
    data class Failure(val reason: String, val file: java.io.File) : UploadResult()
}
