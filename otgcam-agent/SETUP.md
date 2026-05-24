# OTGCam Agent — Setup Guide

## Compatibility
- **Android Studio:** Electric Eel 2022.2.1.20 (AGP 7.3.1, Gradle 7.4.2, Kotlin 1.8.10)
- **Min SDK:** 21 (Android 5.0)
- **Target SDK:** 28 (Android 9.0)
- **Compile SDK:** 33

## 1. Create a Telegram Bot

1. Open Telegram and search for **@BotFather**.
2. Start a chat and send `/newbot`.
3. Follow the prompts to name your bot.
4. BotFather will reply with a token that looks like:
   ```
   123456789:ABCdefGHIjklMNOpqrSTUvwxyz
   ```
   **Copy this token** — it is your **Bot Token**.

## 2. Obtain Your Chat ID

1. Send any message to your new bot in Telegram.
2. Open a browser and visit:
   ```
   https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates
   ```
3. Look for the `"chat":{"id":123456789` field.
   The number (positive or negative for groups) is your **Chat ID**.

## 3. Define an Agent ID

Choose a short alphanumeric identifier for this field device, e.g.:
- `alpha1`
- `cam-field-01`

The Receiver app must use the **exact same** Agent ID to communicate with this device.

## 4. Build the APK

```bash
cd otgcam-agent
./gradlew assembleDebug
```

The APK is output to:
```
app/build/outputs/apk/debug/app-debug.apk
```

## 5. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 6. First-Launch Setup

1. Launch the **OTGCam Agent** app.
2. Enter your **Bot Token**, **Chat ID**, and **Agent ID**.
3. Tap **Save and Continue**.
4. Grant all requested permissions (Camera, Microphone, Storage).
5. Grant **Display over other apps** when prompted.
6. Tap **Start Service**.
7. Lock the phone. The service continues running.

## 7. Grant "Display over other apps"

- Android 6+ (API 23+): Settings → Apps → Special app access → Display over other apps → OTGCam Agent → Allow
- Or tap the in-app prompt which opens the system settings directly.

## 8. Battery Optimisation Exemption

Exempt OTGCam Agent from battery optimisation so the foreground service is not killed.

| Manufacturer | Path |
|---|---|
| Stock Android | Settings → Battery → Battery optimisation → All apps → OTGCam Agent → Don't optimise |
| Samsung | Settings → Battery → Background usage limits → Never sleeping apps → Add |
| Xiaomi / MIUI | Settings → Battery → App battery saver → OTGCam Agent → No restrictions |
| OnePlus | Settings → Battery → Battery optimisation → OTGCam Agent → Don't optimise |
| Huawei | Settings → Battery → App launch → OTGCam Agent → Manage manually → Keep all on |
| Oppo / ColorOS | Settings → Battery → App battery management → OTGCam Agent → Don't optimise |
| Vivo | Settings → Battery → High background power consumption → Allow OTGCam Agent |

Also disable **Adaptive Battery** and **Battery Saver** during operation.

## 9. Troubleshooting

| Issue | Solution |
|---|---|
| Gradle sync fails | Ensure Android Studio Electric Eel is used. Verify `gradle-7.4.2-bin.zip` is downloaded. Check JitPack is accessible. |
| App crashes on launch | Ensure `compileSdk 33` and Kotlin 1.8.10 are configured. Clean and rebuild. |
| "Configuration not found" | Re-open the app and complete the setup fragment. |
| UVC camera not detected | Verify OTG adapter supports USB 2.0/3.0 data. Try a different cable. Check `device_filter.xml` matches your camera's USB class. |
| No vibration on capture | Ensure VIBRATE permission is granted; check Do Not Disturb state. |
| Upload fails repeatedly | Verify bot token and chat ID are correct. Ensure bot is added to the chat. Check internet connectivity. |
| Service stops after screen off | Grant battery exemption (see section 8). Ensure PARTIAL_WAKE_LOCK is held. |
| Headset button not working | Register the app as default media button handler in Bluetooth settings. Increase receiver priority if another app intercepts. |
| Overlay permission denied | Manually enable in system settings (section 7). |
| WebRTC call fails | Ensure both devices can reach Google's STUN server (`stun.l.google.com:19302`). Check firewall/NAT. |
| Bluetooth audio not routing | Toggle Bluetooth SCO manually in AudioManager; ensure headset supports HFP profile. |
| Video recording stops early | Check free storage space. Ensure `WRITE_EXTERNAL_STORAGE` is granted on API ≤ 28. |
| Notification not shown | Create notification channel manually in system settings if IMPORTANCE_LOW is suppressed. |
| Agent ID mismatch | Verify Receiver and Agent use identical Agent IDs. |
| Boot restart not working | Ensure `RECEIVE_BOOT_COMPLETED` permission is granted and app is not force-stopped. |
