# OTGCam

Remote field camera system using Telegram Bot API and WebRTC.

## Projects

- **otgcam-agent** — Field device app with UVC OTG camera, headset controls, and live streaming
- **otgcam-receiver** — Remote operator app with media feed and audio/video calls

## Download Pre-built APKs

Every push to `main` automatically builds both APKs via GitHub Actions.

1. Go to **Actions** tab in this repository
2. Click the latest successful workflow run
3. Scroll down to **Artifacts**
4. Download `otgcam-agent-apk` and `otgcam-receiver-apk`

## Manual Build

### Agent
```bash
cd otgcam-agent
./gradlew assembleDebug
```

### Receiver
```bash
cd otgcam-receiver
./gradlew assembleDebug
```

## Setup

See `otgcam-agent/SETUP.md` and `otgcam-receiver/SETUP.md` for detailed configuration instructions.
