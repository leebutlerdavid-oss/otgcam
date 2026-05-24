package com.otgcam.receiver

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import java.io.File

/**
 * Simple full-screen image viewer for inspecting received photos.
 */
class FullscreenImageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_image)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run {
            finish()
            return
        }

        val ivFullscreen: ImageView = findViewById(R.id.ivFullscreen)
        ivFullscreen.load(File(path))

        val btnClose: ImageView = findViewById(R.id.btnClose)
        btnClose.setOnClickListener { finish() }
    }

    companion object {
        /**
         * Intent extra key for the image file path.
         */
        const val EXTRA_IMAGE_PATH = "extra_image_path"
    }
}
