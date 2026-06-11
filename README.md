# APdemo — WiFi Intelligent Roaming for Android

An Android app that scans nearby WiFi access points (APs), scores them via a backend server, and uses an ONNX ML model to automatically roam to the best available AP.

---

## Features

1. **WiFi Scanning & Scoring** — Periodically scans nearby hotspots and fetches crowd-sourced scores from a backend server.
2. **One-Tap WiFi Switching** — Uses an **accessibility service** to automatically tap the target SSID in the system WiFi list. No manual confirmation dialog needed once the service is enabled.
3. **Smart Roaming** — Automatically switches to the best AP using a **LightGBM ONNX** model with a pairwise ranking algorithm.
4. **Roaming Logs** — Full algorithm execution logs (candidate APs, Borda ranking, pairwise comparison, switch decisions) viewable in real time via the toolbar book icon.
5. **Weak-Signal AP Protection** — APs disappear from the list only after 20 s of absence, preventing UI jitter.

---

## Project Structure

```
app/src/main/java/com/heyu/apdemo2/
├── adapter/           # UI adapters (RecyclerView bindings)
├── connection/        # WiFi connection helpers (legacy, Android 9 and below)
│   ├── WifiConnector.kt      # Deprecated legacy WiFi connector
│   └── PasswordStore.kt      # Saved password storage via SharedPreferences
├── docs/              # Documentation (kept inside source tree for CLAUDE.md compliance)
│   ├── connection/          # WiFi connection docs
│   ├── http/                # HTTP API docs
│   ├── new_ap_selection/    # Model training/conversion docs & scripts
│   └── roaming/             # Roaming algorithm docs
├── model/             # Data models (AccessPoint, ScanResponse, etc.)
├── network/           # HTTP layer (OkHttp + Gson)
├── roaming/           # Roaming algorithm & ONNX inference
│   ├── ApRoamingModel.kt         # ONNX model inference (Video Only, 10 features)
│   ├── ApSelectionManager.kt     # Borda ranking + pairwise best-AP selection
│   ├── ApPairwisePredictor.kt    # Predictor interface
│   └── RoamingLogManager.kt    # File-based logging with real-time listeners
├── scanner/           # WiFi scanning (single-scan mode)
│   └── WifiScanner.kt           # WifiManager startScan() wrapper
├── service/           # Background services
│   ├── ScanForegroundService.kt      # Foreground service: scan loop, score sync, roaming trigger
│   └── WifiAccessibilityService.kt # Accessibility service for auto-connect
└── ui/                # User interface
    ├── MainActivity.kt      # Host activity, roaming log entry, config dialog
    ├── WifiFragment.kt      # WiFi list, tap-to-connect, password dialog
    └── HelpFragment.kt      # Help & troubleshooting guides
```

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin (JVM 11) |
| Min SDK | 24 (Android 7.0) |
| Compile/Target SDK | 36 (Android 16) |
| UI | XML Layouts, RecyclerView, Material Design 3 |
| Networking | OkHttp 4.12.0, Gson 2.10.1 |
| ML Inference | ONNX Runtime Android 1.16.3 |
| Async | Kotlin Coroutines 1.7.3 |
| Permissions | Accompanist Permissions 0.34.0 |

---

## Model

| Property | Value |
|----------|-------|
| File | `assets/ap_roaming_model_video.onnx` |
| Framework | LightGBM → ONNX via `onnxmltools` |
| Type | Pairwise AP ranking (binary classification: AP A > AP B ?) |
| Features | **10** |
| Feature names | `rssi_a`, `rssi_b`, `rssi_diff`, `score_a`, `score_b`, `score_diff`, `prod_a`, `prod_b`, `a_conn`, `b_conn` |
| Normalization | StandardScaler (mean & scale printed by `convert_to_onnx.py`) |
| RSSI range | `[-90, -30]` dBm |
| Score range | `[0, 100]` |
| Output | Probability `0.0 ~ 1.0` that AP A is better than AP B |

> **Training scripts & conversion docs:** `app/src/main/java/com/heyu/apdemo2/docs/new_ap_selection/`

---

## Android Settings Required

### 1. Accessibility Service (Mandatory for Auto-Connect)

The app **cannot** automatically switch WiFi networks without the system accessibility service. The service watches the system WiFi settings screen and auto-taps the target SSID for you.

**Enable path per brand:**

| Brand | Path |
|-------|------|
| Xiaomi / Redmi / MIUI / HyperOS | Settings → Additional settings → Accessibility → Downloaded apps → APdemo → Enable |
| Huawei / Honor / HarmonyOS | Settings → Accessibility features → Accessibility → Installed services → APdemo → Enable |
| OPPO / OnePlus / realme / ColorOS | Settings → Additional settings → Accessibility → Installed apps → APdemo → Enable |
| Vivo / OriginOS / FuntouchOS | Settings → More settings → Accessibility → Downloaded apps → APdemo → Enable |
| Samsung / One UI | Settings → Accessibility → Installed apps → APdemo → Enable |
| Generic Android | Settings → Accessibility → Installed services → APdemo → Enable |

**First launch:** the app will show a prompt dialog. Tap **Go to Settings** and enable it.

---

### 2. Disable Wi-Fi Scan Throttling (Strongly Recommended)

Android caps WiFi scan frequency by default. This directly delays the roaming algorithm's ability to detect better APs in time.

| State | Default Limit |
|-------|---------------|
| Foreground | Max 4 scans per 2 minutes |
| Background | Max 1 scan per 30 minutes |

**How to disable:**

1. Go to **Settings → About phone → Tap “Build number” 7 times** to enable Developer Options.
2. Return to **Settings → System → Developer Options**.
3. Find **“Wi-Fi Scan Throttling”** and turn it **OFF**.

> **Tip:** On some Samsung devices the option is under **Settings → Connections → Wi-Fi → Advanced → Wi-Fi scan throttling**.  
> **Tip:** If you can't find the option, try enabling **USB Debugging** first—some OEMs hide advanced toggles until USB debugging is on.

---

### 3. Allow Background Running (Domestic ROMs)

The app uses a foreground service (`FOREGROUND_SERVICE_TYPE_LOCATION`) plus `WakeLock` and `AlarmManager` to survive background. Most Chinese OEM ROMs (MIUI, ColorOS, HarmonyOS, etc.) add extra restrictions.

Configure per brand:

| Brand | Steps |
|-------|-------|
| **Xiaomi / Redmi / MIUI / HyperOS** | Settings → Apps → App management → APdemo → Battery saver → **Unrestricted**. Also Settings → Permissions → **Autostart** → Enable. |
| **Huawei / Honor / HarmonyOS** | Settings → Apps → App launch → APdemo → Disable “Manage automatically” → Manually enable **Allow auto-launch**, **Allow background activity**, **Allow secondary launch**. |
| **OPPO / OnePlus / realme / ColorOS** | Settings → Battery → Battery protection → APdemo → **Disable restriction**. Also Settings → Apps → APdemo → Battery → **Allow background activity**. |
| **Vivo / OriginOS / FuntouchOS** | Settings → Battery → High background power consumption → **Allow**. Also iManager → App management → Permission management → APdemo → **Allow background running**. |
| **Samsung / One UI** | Settings → Apps → APdemo → Battery → **Unrestricted**. |
| **Generic** | Settings → Apps → APdemo → Battery → **Don't restrict background activity**. In the recent-apps view, long-press the app card and tap **Lock** to prevent clearing. |

---

### 4. Permissions to Grant

The app requests the following permissions at runtime:

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Required by Android for WiFi scanning |
| `ACCESS_BACKGROUND_LOCATION` | Scanning while the app is backgrounded |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `NEARBY_WIFI_DEVICES` | WiFi scanning on Android 13+ |

**Manifest-only permissions** (no prompt):

| Permission | Purpose |
|------------|---------|
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Toggle & read WiFi state |
| `INTERNET` | Backend HTTP communication |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | Foreground scan service |
| `WAKE_LOCK` | Keep CPU awake during scan/upload |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt user to whitelist app from Doze |
| `ACCESS_NETWORK_STATE` / `CHANGE_NETWORK_STATE` | Network connectivity checks |

---

## How to Use

1. **First launch** — Grant location & notification permissions. Follow the prompt to enable the **Accessibility Service**.
2. **Configure server** — Tap the top-right **settings (gear)** icon. Enter:
   - **Server IP** — your backend IP address
   - **Port** — your backend port
   - **Scan Interval** — how often to perform a hardware WiFi scan (default 35 s)
   - **Switch Cooldown** — minimum seconds between two automatic switches (default 5 s)
3. **Connect to a WiFi** — Tap any AP in the list. If encrypted, enter the password once; it is saved automatically.
4. **Enable auto roaming** — Tap the **roaming** icon in the toolbar. The app will now periodically evaluate all visible APs and switch to the best one automatically.
5. **View logs** — Tap the **book** icon in the toolbar to see real-time roaming decisions.

---

## Backend API

The app expects a simple HTTP server that accepts POST `/ap_scores` with a JSON array of APs and returns scores.

**Request (POST /ap_scores):**
```json
[
  {"ssid": "AP_1", "bssid": "aa:bb:cc:dd:ee:ff", "rssi": -45, "frequency": 5180},
  {"ssid": "AP_2", "bssid": "11:22:33:44:55:66", "rssi": -60, "frequency": 2412}
]
```

**Response:**
```json
{
  "results": [
    {"ssid": "AP_1", "score": 87, "reason": "Stable, low latency"},
    {"ssid": "AP_2", "score": 62, "reason": "Moderate congestion"}
  ]
}
```

If the server is unavailable, the app falls back to a default score of **50** and still evaluates roaming.

---

## Known Limitations

1. **Accessibility service must be manually enabled** — Users must toggle it in system settings; the app cannot enable it programmatically.
2. **Model feature simplification** — Connection-state features (`a_conn`, `b_conn`) use a simplified `RSSI > -90` heuristic instead of real link-state data.
3. **Switch cooldown is global** — The cooldown period applies to both manual and automatic switches.
4. **Android default background scan limit** — Without disabling Wi-Fi Scan Throttling, background scans are heavily rate-limited.

---

## Documentation

| Document | Description |
|----------|-------------|
| `docs/PROJECT_DOCS.md` | Main project documentation, module status, and feature checklist |
| `docs/connection/WIFI_CONNECTION_SPECIFIER.md` | Historical WiFi connection docs (current implementation uses accessibility service) |
| `docs/http/http_frontend.md` | Backend API contract |
| `docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md` | Roaming algorithm & logging system deep dive |
| `docs/roaming/MODEL_INTEGRATION_GUIDE.md` | How to swap ONNX models and update scaler parameters |
| `docs/new_ap_selection/README.md` | LightGBM model features, conversion to ONNX, and integration steps |

---

## License

Internal use only — not open-sourced.
