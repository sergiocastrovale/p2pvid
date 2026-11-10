# P2Pvid

Peer-to-peer video calls between two Android devices over the local network: no account, no server, no internet.

One device starts a call and gets an invite code. The other enters or scans the code, and the call starts. Video and audio go directly between the two devices' LAN addresses, so it works offline: on the same Wi-Fi, or with one phone connected to the other's hotspot.

## Features

- **Invite code**: 16 characters (`XXXX-XXXX-XXXX-XXXX`), shown with a QR code and Copy/Share buttons. The code contains everything needed to reach the host, so no discovery or server is involved. A wrong code is rejected.
- **View modes**
  - **Dynamic**: whoever is speaking fills the screen; the other person sits in a small draggable window.
  - **Locked**: stays on one person. Tap the small window to lock onto them.
  - **Dual**: both side by side, center-cropped. Landscape only; the app rotates for it.
- **Director mode**: whoever switches it on controls the view on both screens (mode, who is locked, who is shown as speaker). The other side can take over by switching it on themselves.
- **Recording**: records what is on screen, including mode changes, with both sides' audio mixed. Saved as MP4 to `Movies/P2Pvid`. The other device shows that you are recording.
- **Settings**: outgoing stream quality (360p up to 1080p @ 12 Mbps, applies mid-call), starting view, starting camera, keep screen on.

## Install

- Download or build `p2pvid-1.0.0-debug.apk` (see [Build](#build)).
- On the phone, allow installing apps from unknown sources for your browser or file manager.
- Open the APK and install it. Or, with USB debugging on: `adb install p2pvid-1.0.0-debug.apk`.
- Do the same on the second phone.
- Put both phones on the same Wi-Fi, or connect one to the other's hotspot. Mobile data is not used.
- Open P2Pvid on both phones and allow camera, microphone and notifications.
- Phone A: **New call**. Phone B: type the code or tap the scan icon and scan A's QR code, then **Join**.

Requires Android 8.0 (API 26) or newer.

**Troubleshooting**: if the phones can't reach each other, the network may isolate devices from each other (common on guest and office Wi-Fi). Use a phone hotspot instead.

## Build

### Requirements

- JDK 17 or newer
- Android SDK with platform `android-35` and build-tools 35
- No Gradle install needed; the wrapper downloads it

Point Gradle at the SDK with `local.properties` (not committed):

```properties
sdk.dir=/path/to/Android/Sdk
```

or set `ANDROID_HOME`.

### Commands

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/p2pvid-<version>-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleRelease      # app/build/outputs/apk/release/p2pvid-<version>.apk (minified, debug-signed)
./gradlew installDebug         # install on a connected device
```

The release build is signed with the debug key, so it is fine for sideloading but not for publishing. Add a real signing config in `app/build.gradle.kts` before distributing.

### Stack

Kotlin, Jetpack Compose (Material 3), WebRTC (`io.getstream:stream-webrtc-android`), ZXing for QR codes, DataStore for settings, MediaCodec + MediaMuxer for recording.

### Project layout

```
app/src/main/java/com/kp/p2pvid/
├─ net/        InviteCode (encode/parse, LAN IP), Signaling (authenticated TCP for SDP/ICE)
├─ rtc/        RtcSession: peer connection, camera/mic, data channel, quality
├─ view/       ViewState, DirectorState, SpeakerDetector, Layout, Control messages
├─ rec/        Recorder (GL compositor → H.264, audio mix → AAC → MP4), MediaOutput
├─ call/       CallViewModel (orchestration), CallService (foreground), AudioRouter
├─ data/       Prefs, Quality presets
└─ ui/         Home, Call, Settings screens
```

### How a call is set up

1. The host opens a TCP server on a random port and encodes `IPv4 + port + 4-byte secret` as the 16-character Crockford Base32 invite code.
2. The joiner connects. Both prove they know the secret with an HMAC-SHA256 challenge; every later message is HMAC-signed.
3. SDP offer/answer and ICE candidates go over that socket. WebRTC is set up with no STUN/TURN servers, so only LAN candidates exist.
4. View, director and recording messages go over a WebRTC data channel. Media is encrypted by DTLS-SRTP.

The 32-bit secret stops casual guessing on a shared network, not a determined attacker on the same LAN.

### Testing

Unit tests cover invite codes, signaling handshake and tamper rejection, director takeover and simultaneous claims, speaker detection, layout math, and audio mixing. Camera, mic and call behaviour need two physical devices; the emulator's NAT networking can't connect two emulators directly.
