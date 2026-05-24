package com.otgcam.receiver

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.otgcam.receiver.ui.SetupFragment

/**
 * Entry point for the OTGCam Receiver application.
 * Routes to setup on first launch or directly to the media feed on subsequent launches.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!isSetupComplete()) {
            showSetupFragment()
        } else {
            navigateToMediaFeed()
        }
    }

    private fun isSetupComplete(): Boolean {
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
            prefs.getBoolean("setup_complete", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun showSetupFragment() {
        val fragment = SetupFragment.newInstance()
        fragment.onSetupComplete = {
            navigateToMediaFeed()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun navigateToMediaFeed() {
        startActivity(android.content.Intent(this, MediaFeedActivity::class.java))
        finish()
    }
}
