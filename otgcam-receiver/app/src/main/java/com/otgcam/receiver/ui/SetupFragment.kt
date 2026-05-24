package com.otgcam.receiver.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.snackbar.Snackbar
import com.otgcam.receiver.R
import com.otgcam.receiver.databinding.FragmentSetupBinding

/**
 * First-launch fragment that collects Telegram credentials and agent identifier,
 * persisting them in EncryptedSharedPreferences.
 */
class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is null")

    /**
     * Callback invoked when setup is successfully completed.
     */
    var onSetupComplete: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSave.setOnClickListener { saveConfiguration() }
    }

    private fun saveConfiguration() {
        val botToken = binding.etBotToken.text.toString().trim()
        val chatId = binding.etChatId.text.toString().trim()
        val agentId = binding.etAgentId.text.toString().trim()

        if (botToken.isEmpty()) {
            binding.tilBotToken.error = getString(R.string.error_field_required)
            return
        } else {
            binding.tilBotToken.error = null
        }

        if (chatId.isEmpty()) {
            binding.tilChatId.error = getString(R.string.error_field_required)
            return
        } else {
            binding.tilChatId.error = null
        }

        if (agentId.isEmpty()) {
            binding.tilAgentId.error = getString(R.string.error_field_required)
            return
        } else {
            binding.tilAgentId.error = null
        }

        val context = requireContext()
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "otgcam_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit()
                .putString("bot_token", botToken)
                .putString("chat_id", chatId)
                .putString("agent_id", agentId)
                .putBoolean("setup_complete", true)
                .apply()
            onSetupComplete?.invoke()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Failed to save credentials: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Factory method for creating a new SetupFragment instance.
         */
        fun newInstance(): SetupFragment = SetupFragment()
    }
}
