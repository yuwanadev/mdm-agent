# YuwanaDev MDM - Android Agent

The Android Agent for the YuwanaDev Mobile Device Management (MDM) platform. This application runs on managed Android devices to collect telemetry, execute commands, and establish real-time WebRTC connections for screen mirroring.

## Features
- **Real-time Telemetry**: Reports battery health, memory usage, network state, and foreground application.
- **Remote Control**: Support for remote lock, factory reset, and enabling/disabling developer options.
- **Screen Mirroring**: Uses WebRTC to stream the device screen to the MDM Dashboard.
- **Secure Communication**: Persistent WebSocket connection to the MDM backend.

## Tech Stack
- **Language**: Kotlin
- **SDK Target**: Android 34 (Minimum SDK 26)
- **Networking**: OkHttp (WebSockets)
- **Streaming**: WebRTC
- **Local Storage**: Room Database & DataStore

## Build & Run
To build the application locally:
```bash
./gradlew assembleDebug
```

## Required Permissions
After installing the agent, you must grant the following advanced permissions via ADB to enable full MDM control (like screen mirroring without prompts and system settings modification):

```bash
adb shell cmd appops set com.yuwanadev.mdm PROJECT_MEDIA allow
adb shell pm grant com.yuwanadev.mdm android.permission.WRITE_SECURE_SETTINGS
```
