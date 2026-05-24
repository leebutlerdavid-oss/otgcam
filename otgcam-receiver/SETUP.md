# OTGCam Receiver — Setup Guide

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

Choose a short alphanumeric identifier that **exactly matches** the Agent app's configured Agent ID, e.g.:
- `alpha1`
- `cam-field-01`

The Receiver and Agent must use the **same** Agent ID for WebRTC signaling to work.

## 4. Build the APK

```bash
cd otgcam-receiver
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

1. Launch the **OTGCam Receiver** app.
2. Enter your **Bot Token**, **Chat ID**, and **Agent ID**.
3. Tap **Save and Continue**.
4. The media feed opens automatically.
5. Grant **Camera** and **Microphone** permissions when prompted (required for video calls).

## 7. Using the Media Feed

- Photos and videos sent by the Agent appear at the **top** of the feed in real time.
- Tap a **photo** to open it in full-screen view.
- Tap a **video** to play it with the system video player.
- The connection status chip shows **Polling**, **Connected**, or **Error**.

## 8. Initiating a Call

- Tap **Audio Call** to start a voice-only call with the Agent.
- Tap **Video Call** to start a video call. The Agent's UVC camera will stream to your screen.
- The Agent auto-accepts all incoming calls.
- During a call, use the **mute**, **speaker**, and **end call** buttons.

## 9. Battery Optimisation Exemption

Exempt OTGCam Receiver from battery optimisation so polling is not interrupted.

| Manufacturer | Path |
|---|---|
| Stock Android | Settings → Battery → Battery optimisation → All apps → OTGCam Receiver → Don't optimise |
| Samsung | Settings → Battery → Background usage limits → Never sleeping apps → Add |
| Xiaomi / MIUI | Settings → Battery → App battery saver → OTGCam Receiver → No restrictions |
| OnePlus | Settings → Battery → Battery optimisation → OTGCam Receiver → Don't optimise |
| Huawei | Settings → Battery → App launch → OTGCam Receiver → Manage manually → Keep all on |
| Oppo / ColorOS | Settings → Battery → App battery management → OTGCam Receiver → Don't optimise |
| Vivo | Settings → Battery → High background power consumption → Allow OTGCam Receiver |

## 10. Troubleshooting

| Issue | Solution |
|---|---|
| Gradle sync fails | Ensure Android Studio Electric Eel is used. Verify `gradle-7.4.2-bin.zip` is downloaded. |
| App crashes on launch | Ensure `compileSdk 33` and Kotlin 1.8.10 are configured. Clean and rebuild. |
| "Configuration missing" | Re-launch app and complete setup. Verify credentials are saved. |
| No media appearing in feed | Verify bot token and chat ID match the Agent. Ensure Agent is uploading successfully. Check internet connectivity. |
| Call fails to connect | Ensure both devices can reach Google's STUN server (`stun.l.google.com:19302`). Check NAT/firewall. |
| Agent does not auto-accept | Verify Agent ID matches exactly on both sides. Check Agent is running and polling. |
| No audio during call | Grant RECORD_AUDIO permission. Check Bluetooth headset routing if applicable. |
| Video call shows black screen | Grant CAMERA permission. Ensure Agent's UVC camera is connected. |
| Video playback fails | Ensure FileProvider authority matches `com.otgcam.receiver.fileprovider`. |
| Polling stops after screen off | Grant battery exemption (see section 9). |
| Gradle sync fails | Verify network access to Maven Central. Check `settings.gradle` repositories. |
| WebRTC library not found | Verify `io.getstream:stream-webrtc-android:1.1.3` is in dependencies. Clean and rebuild. |
| Fullscreen image is blank | Check that the photo file exists in `filesDir/photos/`. |
| Chronometer not starting | Ensure SDP answer is received from Agent. Check signaling flow in logs. |
