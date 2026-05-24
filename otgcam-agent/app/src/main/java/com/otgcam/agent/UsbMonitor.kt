package com.otgcam.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Monitors OS-level UVC camera attachment and detachment independently
 * from the camera library to provide early haptic feedback.
 */
class UsbMonitor(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUvcCamera(device)) {
                        VibrationHelper.vibrateUvcEvent(context)
                        broadcastStatus(context, "UVC camera attached: ${device.productName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUvcCamera(device)) {
                        VibrationHelper.vibrateUvcEvent(context)
                        broadcastStatus(context, "UVC camera detached: ${device.productName}")
                    }
                }
            }
        }
    }

    /**
     * Register the USB broadcast receiver.
     */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter)
    }

    /**
     * Unregister the USB broadcast receiver. Safe to call multiple times.
     */
    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered.
        }
    }

    private fun isUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == 14) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    private fun broadcastStatus(ctx: Context, message: String) {
        val intent = Intent(CameraService.ACTION_STATUS_UPDATE).apply {
            putExtra(CameraService.EXTRA_STATUS_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}
