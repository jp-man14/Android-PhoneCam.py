# PhoneCam — Android Phone Camera → OBS Virtual Camera

Stream any Android phone's camera directly into OBS over USB or Wi-Fi with no cloud, no third-party servers, and no paid subscriptions. Verified at 1080p60 end-to-end (Samsung Galaxy S24 Ultra).

---

## Features

- **1080p60 H.264** hardware encoding (MediaCodec), tuned CBR with realtime priority and no B-frames
- **USB and Wi-Fi simultaneously** — `adb forward` for lowest latency, or direct LAN with no ADB needed
- **Zoom slider** (ultrawide ~0.6x through 10x on supported phones), **tap-to-focus**, continuous autofocus, torch toggle
- Front/back camera switch with mirroring, auto-rotate handling (portrait pillarboxed, never stretched)
- Live FPS badge, client count, keyframe-aware recovery (fresh IDR requested if congestion drops one)
- System-wide virtual camera — works in OBS, Zoom, Teams, Discord, browsers

---

## How It Works

```
Phone Camera
    │
    ▼
[PhoneCam Android App]
    │  H.264 Annex-B over TCP (port 8080)
    │  HW encode 1080p60 ~10Mbps, rotation as metadata, 720p-capped preview
    │
    ▼  USB-C cable (adb forward → localhost:8080)
    │  …or direct Wi-Fi (phone LAN IP:8080, shown in the app)
    ▼
[phonecam_client.py]
    │  PyAV decode in a child process → shared memory
    │  letterbox fit → feeds frames into
    │
    ▼
[OBS Virtual Camera driver]
    │
    ▼
OBS Studio (Video Capture Device source) — or any camera app
```

---

## Requirements

### Phone (any Android)
- Android 8.0+ (API 26+); Android 11+ for the full ultrawide zoom range
- The **PhoneCam APK** installed
- USB Debugging enabled (USB mode only — Wi-Fi mode needs none of this)

### Windows PC
- [OBS Studio](https://obsproject.com) installed (provides the virtual camera driver)
- [Python 3.10+](https://python.org) with pip
- [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) (for `adb`, USB mode only)
- USB-C data cable (USB mode)

---

## Step 1 — Enable USB Debugging (USB mode only)

Skip this whole step if you stream over Wi-Fi.

1. Open **Settings → About phone → Software information**
2. Tap **Build number** 7 times until "Developer mode unlocked" appears
3. Go to **Settings → Developer options**
4. Enable **USB debugging**
5. Connect phone to PC via USB-C data cable (charge-only cables won't work)
6. When prompted on the phone: tap **Allow** on the "Allow USB Debugging?" dialog
   - Check "Always allow from this computer" to avoid repeated prompts

---

## Step 2 — Install Android SDK Platform Tools (ADB, USB mode only)

1. Download from: https://developer.android.com/tools/releases/platform-tools
2. Extract the zip (e.g., to `C:\platform-tools\`)
3. Add to PATH:
   - Press `Win + R` → type `sysdm.cpl` → Environment Variables
   - Under "System variables", find `Path` → Edit → New → paste your folder path
   - Click OK on all dialogs
4. Open a new Command Prompt and verify: `adb version`

---

## Step 3 — Build and install the Android APK

### Option A — Build with Android Studio (recommended)
1. Install [Android Studio](https://developer.android.com/studio)
2. Open the `android-app/` folder as a project
3. Let Gradle sync complete (needs the Android SDK; first build downloads it)
4. Connect your phone (USB debugging on)
5. Press **Run ▶** (or `Shift+F10`)

### Option B — Build from command line
```bat
cd android-app
gradlew assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

## Step 4 — Set up the Windows client

Install Python dependencies:
```bat
pip install pyvirtualcam opencv-python numpy av
```

Or just run `START_PHONECAM.bat` — it handles installation automatically.

Developers: `windows-client/test_phonecam_client.py` holds the unit tests
(`python -m unittest test_phonecam_client`); the client is `ruff`-clean.

---

## Step 5 — Stream!

### On your phone:
1. Open the **PhoneCam** app
2. Choose quality: **1080p 60fps** (default), 1080p 30fps, 720p 60fps, or 720p 30fps
3. Hold the phone **landscape** for fullscreen (portrait is pillarboxed, never stretched)
4. Optional: drag the **zoom slider**, tap the preview to focus, toggle **Torch**, **Flip** for front camera
5. Tap **"Start Streaming"**
6. The green dot plus `Streaming H.264 :8080 1920x1080 @60fps` confirms it's live.
   The app also shows its Wi-Fi address (e.g. `WiFi 192.168.0.95:8080`).
7. **Keep PhoneCam in the foreground with the screen on.** Streaming stops if
   the app is backgrounded or the phone locks (known limitation).

### On your Windows PC — USB (lowest latency):

**Quickest way:**
```bat
double-click  windows-client\START_PHONECAM.bat
```

**Or manually:**
```bat
adb forward tcp:8080 tcp:8080
python windows-client\phonecam_client.py
```

### On your Windows PC — Wi-Fi (no cable, no ADB):

1. Read the **current** Wi-Fi IP from the app screen (DHCP can change it —
   always re-check before connecting).
2. Phone and PC must share a network; **5GHz** is strongly preferred over 2.4GHz.
3. One command:
```bat
windows-client\START_PHONECAM.bat <phone-wifi-ip>
```
Or manually:
```bat
python windows-client\phonecam_client.py --host <phone-ip> --no-adb
```
4. The client waits patiently for the first frame (Wi-Fi can take up to a
   minute on a congested network) — leave it alone, watch for `[OK] Stream live!`.

Verified measurements (Galaxy S24 Ultra): **~60fps USB**, **~59fps clean Wi-Fi**.

### In OBS Studio:
1. Click **+** under Sources
2. Select **Video Capture Device**
3. Name it (e.g., "Phone Camera")
4. In the device dropdown, select **"OBS Virtual Camera"**
5. Click OK — you should see your phone's live feed!

---

## Options and Flags

```
python phonecam_client.py [OPTIONS]

  --host HOST       Stream host IP (default: 127.0.0.1; use phone IP for Wi-Fi)
  --port PORT       Stream port (default: 8080)
  --width W         Output width (default: 1920)
  --height H        Output height (default: 1080)
  --fps FPS         Virtual camera FPS (default: 60)
  --no-adb          Skip ADB setup (use with --host for Wi-Fi)
  --overlay         Show FPS/timestamp overlay on the stream
  --preview         Open a local OpenCV preview window
```

**Example — 720p stream:**
```bat
python phonecam_client.py --width 1280 --height 720
```

**Example — with preview window and overlay:**
```bat
python phonecam_client.py --preview --overlay
```

---

## Troubleshooting

### "No frame after 20s"
- Make sure the PhoneCam app shows `Streaming H.264 ...` **right now** (re-check —
  the stream stops if the app leaves the foreground)
- USB: run `adb forward --list` — you should see `tcp:8080 tcp:8080`
- Wi-Fi: make sure the `--host` IP matches the app's Wi-Fi line and both devices share a network
- Try `adb kill-server` then `adb start-server`, then re-run the bat file

### Wi-Fi connects but stalls / keeps reconnecting
- First suspect the **radio link, not the app**: ping the phone (`ping <phone-ip>`).
  Steady <10ms is healthy; swings like 6ms → 150ms+ mean congestion, weak signal,
  or a crowded 2.4GHz channel — move to **5GHz** and closer to the router.
- The app holds a high-performance Wi-Fi lock while streaming, but it can't fix
  a saturated channel: drop to 720p30 to halve the bitrate.
- Guest/hotel networks with client isolation block phone↔PC traffic — use a
  private WLAN or USB instead.

### "Connection refused" instantly (Wi-Fi)
- You're almost certainly talking to the **wrong address**: without flags the
  client dials `127.0.0.1` (itself), which refuses when no ADB forward exists.
  Use `START_PHONECAM.bat <phone-wifi-ip>` or
  `phonecam_client.py --host <phone-ip> --no-adb`.
- If flags are right: the phone's IP may have changed (DHCP) — re-read it from
  the app — or a guest network / VPN is isolating the devices.

### "Virtual camera error" / "OBS Virtual Camera not found"
- Open OBS Studio at least once — it registers the virtual camera driver
- OBS 28+ has virtual cam built-in; no extra setup needed

### App crashes on phone
- Make sure Camera permission is granted: Settings → Apps → PhoneCam → Permissions
- Try a lower quality setting (720p) if you get camera errors
- If a lens/mode isn't supported, the app says so (e.g. "Lens caps at 30fps") — pick another combination

### ADB device not found
- Try a different USB-C cable (some are charge-only)
- Check the phone's USB mode is File Transfer / MTP in the USB notification
- Try `adb kill-server` and replug the cable

### High latency
- USB is the lowest-latency option (~150–300ms typical with H.264)
- Reduce quality to 720p for lower latency and less heat
- If the phone gets warm on long 60fps sessions, fps may settle in the 40s —
  cool the phone or drop to 1080p30
- Ensure nothing else is using port 8080 on either device

### Stream stops when the phone sleeps
- Known limitation: streaming currently stops if the app is backgrounded or the
  phone locks. Keep PhoneCam in the foreground while streaming.

---

## File Structure

```
phone-cam/
├── android-app/              ← Android Studio project
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/phonecam/
│       │   └── MainActivity.java   ← Camera capture + H.264 server
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/ (strings, colors), drawable/
│
├── windows-client/
│   ├── phonecam_client.py    ← Main Python client (PyAV decode → virtual cam)
│   ├── test_phonecam_client.py ← Unit tests (python -m unittest test_phonecam_client)
│   └── START_PHONECAM.bat    ← One-click launcher
│
├── LICENSE.md                ← MIT License
└── README.md                 ← This file
```

---

## Privacy

Everything runs locally — over USB the camera data never leaves the cable; over Wi-Fi it never leaves your LAN. No internet connection required, no accounts, no cloud.

> **Wi-Fi mode note:** the phone's stream server binds all network interfaces with
> **no authentication**. Over USB (`adb forward`) the port is localhost-only and
> safe. Over Wi-Fi, anyone on the same network with your phone's IP and port can
> view the camera feed — only stream on networks you trust.

---
