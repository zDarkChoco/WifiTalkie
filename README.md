# WifiTalkie

**Local Wi-Fi Half-Duplex Walkie-Talkie for Android** — Designed with a **Pure OLED Dark Mode (`#000000`)**, **Liquid Glass** specular refraction cards, **Google Material 3 Dynamic Colors**, and a **Floating Button** interface.

---

## Download APK

- **Direct APK in Repository**: [`WifiTalkie.apk`](./WifiTalkie.apk) / [`releases/WifiTalkie-v1.0.0.apk`](./releases/WifiTalkie-v1.0.0.apk)
- **GitHub Releases**: Available on the [Releases](https://github.com/zDarkChoco/WifiTalkie/releases) page.

---

## Core Walkie-Talkie Functionality

1. **Zero-Server Local Wi-Fi Communication**
   - Communicates directly between 2 or more Android devices connected to the same local Wi-Fi router or mobile hotspot using UDP port `50005` (`239.255.42.99` multicast + subnet broadcast + automatic unicast peer mesh).
   - No internet connection, account, or external server required.

2. **Multi-User Detection & 16-Channel Selection (`CH 01` – `CH 16`)**
   - Automatically detects all active `WifiTalkie` users on the local Wi-Fi and displays their callsign, IP address, current channel, and live status (`STANDBY`, `TALKING`, `LISTENING`) in the floating **Detected Wi-Fi Users** radar bar.
   - Select from **16 tactical channels (`CH 01` – `CH 16`)** via the horizontal floating channel pills or the **ALL 16 CH** channel matrix modal.
   - Tap any detected user chip or tap **SCAN PEERS** to immediately tune to their active channel.

3. **Push-To-Talk (PTT) Method Only**
   - Strictly push-and-hold on the centerpiece **Floating Liquid Glass PTT Orb** (or hold the physical **Volume Down** key) to talk, and release to listen.

4. **Strict Half-Duplex Floor Control (Wait for Others to Finish Talking)**
   - Only one user on a channel can transmit at a time.
   - When another user on your channel is speaking, the channel locks into **`CHANNEL BUSY • WAIT FOR [USER]`** and prevents local transmission until the speaker finishes and releases PTT.
   - Includes deterministic millisecond timestamp + device-ID tie-breaking if two users press PTT simultaneously, plus a 700ms watchdog auto-release if a UDP release packet is dropped.

5. **Authentic Walkie-Talkie Beep Tones (Before Talking & Before Hearing)**
   - **Pre-Talk Start Beep**: Synthesizes a crisp rising two-tone walkie-talkie chirp (`1200 Hz → 1680 Hz`) when you press PTT **before** opening the microphone.
   - **Pre-Hear Incoming Beep**: Plays an incoming alert chirp (`1060 Hz → 1480 Hz`) when a peer starts transmitting **before** playing their voice stream.
   - **Roger Beep**: Plays a classic walkie-talkie end-of-transmission double pip (`1568 Hz → 1318 Hz`) when either party finishes talking.
   - **Busy Lockout Tone**: Plays a low double tone (`380 Hz → 300 Hz`) with haptic shake feedback if you press PTT while another user is still talking.

---

## Modern UI Design

- **OLED Dark Mode Only**: Uses a pure `#000000` black canvas across >65% of the screen so OLED pixels turn completely off for superior battery life during standby.
- **Liquid Glass Effect**: Custom hardware-accelerated [`LiquidGlassDrawable`](app/src/main/java/com/zdarkchoco/wifitalkie/ui/LiquidGlassDrawable.java) and [`LiquidPttOrbView`](app/src/main/java/com/zdarkchoco/wifitalkie/ui/LiquidPttOrbView.java) render translucent dark frosted glass with diagonal specular refraction rims, top-edge curved lens highlights, and dual phase-shifted internal liquid waves that react in real time to voice amplitude.
- **Google Material 3 Dynamic Color Palette**:
  - **Standby / Ready**: Material 3 Aqua & Primary Blue (`#5CE1E6` / `#A8C7FA`)
  - **Pre-Talk Beep**: Material 3 Warm Gold (`#FFDF9E`)
  - **Transmitting (TX)**: Material 3 Emerald (`#6DD58C`)
  - **Receiving / Busy Lock (RX)**: Material 3 Amber & Coral (`#FFB784` / `#FF897D`)
- **Floating Button Style**: Floating top status & quick-toggle pills (`ID`, `SPK ON / EARPIECE`, `BEEP ON`), floating channel pills, floating 28-bar symmetrical spectrum visualizer ([`WaveformVisualizerView`](app/src/main/java/com/zdarkchoco/wifitalkie/ui/WaveformVisualizerView.java)), floating 3D PTT orb, and floating bottom action dock (`CH -`, `CALL BEEP`, `SCAN PEERS`, `CH +`).

---

## Project Structure

```
WifiTalkie/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/zdarkchoco/wifitalkie/
│   │   ├── MainActivity.java
│   │   ├── audio/
│   │   │   ├── AudioStreamManager.java    # 16kHz 16-bit PCM 20ms capture & jitter-buffered playback
│   │   │   └── BeepSynthesizer.java       # Zero-click PCM tone synthesizer (pre-talk, pre-hear, roger, busy)
│   │   ├── net/
│   │   │   ├── FloorController.java       # Half-duplex PTT floor lock & tie-breaker state machine
│   │   │   ├── PeerInfo.java              # Discovered Wi-Fi peer model
│   │   │   ├── WalkiePacket.java          # Binary UDP packet wire format ("WFTK")
│   │   │   └── WifiTalkieEngine.java      # Multicast + Broadcast + Unicast UDP engine
│   │   ├── service/
│   │   │   └── WalkieTalkieService.java   # Foreground service keeping Wi-Fi multicast & socket active
│   │   └── ui/
│   │       ├── LiquidGlassDrawable.java   # Specular refraction liquid glass drawable
│   │       ├── LiquidPttOrbView.java      # 3D liquid wave Push-To-Talk floating orb
│   │       ├── MaterialOledPalette.java   # OLED Black + Material 3 Dark color tokens
│   │       └── WaveformVisualizerView.java# 28-bar real-time audio spectrum & channel monitor
│   └── res/                               # Adaptive icons, OLED Dark theme, strings & colors
├── app/src/test/java/com/zdarkchoco/wifitalkie/
│   └── WalkieProtocolTest.java            # Automated protocol & half-duplex floor control tests
├── build-apk.sh                           # Self-contained APK build, sign (v2/v3), and test script
├── WifiTalkie.apk                         # Signed installable Android APK
└── releases/WifiTalkie-v1.0.0.apk         # Versioned signed Android APK
```

---

## Building from Source

Run the self-contained build script (compiles resources with `aapt2`, compiles Java with `javac`, dexes with `D8`, aligns with `zipalign`, signs with `apksigner`, and runs `WalkieProtocolTest`):

```bash
./build-apk.sh
```
