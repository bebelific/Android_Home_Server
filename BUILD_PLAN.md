# Android Home Server — Build Plan
**Project:** Android Printing Server → Android Home Server  
**Target:** Old Android phone (API 21+) as 24/7 LAN appliance  
**Core philosophy:** Toggleable services, minimal resource usage, hardware reuse

---

## 1. Service Portfolio

| Service | Protocol | Port | Toggle | Module |
|---------|----------|------|--------|--------|
| **Print Server** | Raw TCP (9100), IPP (631) | 9100/631 | ✅ | `core-print` + `core-network` |
| **File Sharing** | SMB/CIFS, WebDAV, FTP | 445/8080/21 | ✅ | `core-files` |
| **Webcam Stream** | MJPEG (HTTP), RTSP, WebRTC | 8081/554/8082 | ✅ | `core-camera` |
| **Status/Config** | HTTP + WebSocket | 8083 | Always on | `app` |

All services bind to LAN only (`0.0.0.0` on Wi-Fi interface), never WAN.

---

## 2. Module Architecture (Updated)

```
AndroidHomeServer/
├── app/                      # UI, service orchestrator, notification, settings
├── core-common/              # Logging, Result, StateFlow helpers, NetworkUtils
├── core-print/               # Existing: TCP 9100 ingress → USB pass-through
├── core-network/             # Existing: TcpPrintServer, base IngressListener
├── core-usb/                 # Existing: UsbPrinterManager, UsbPrinterSession
├── core-queue/               # Existing: PrintJob, JobQueue
├── core-files/               # NEW: SMB/WebDAV/FTP server, storage abstraction
├── core-camera/              # NEW: Camera2 API → MJPEG/RTSP/WebRTC encoder
├── core-power/               # NEW: WakeLock, WifiLock, battery health, charge limit
└── core-discovery/           # NEW: mDNS/SSDP for service advertisement
```

**New modules:**
- `core-files` — `com.printserver.core.files`
- `core-camera` — `com.printserver.core.camera`
- `core-power` — `com.printserver.core.power`
- `core-discovery` — `com.printserver.core.discovery`

---

## 3. Feature Toggle Design

### Per-Service State Machine
```
DISABLED → STARTING → RUNNING → STOPPING → DISABLED
                    ↓
               ERROR (auto-retry with backoff)
```

### Toggle Persistence
- `DataStore` (Preferences) for toggle states — survives reboot
- `ServerRepository` reads on init, starts enabled services
- UI observes `StateFlow<ServiceState>` per service

### Service Registry (in `ServerRepository`)
```kotlin
sealed interface Service {
    val id: String
    val displayName: String
    val defaultPort: Int
    val dependencies: List<String>  // e.g., camera needs "storage" for recordings
    suspend fun start(context: Context): Result<Unit>
    suspend fun stop(): Result<Unit>
    val state: StateFlow<ServiceState>
}
```

Implemented services:
- `PrintService` (wraps existing pipeline)
- `FileService` (SMB + WebDAV + FTP)
- `CameraService` (MJPEG + RTSP + WebRTC)
- `DiscoveryService` (mDNS for all)

---

## 4. File Sharing (`core-files`)

### Protocols & Libraries
| Protocol | Library | Port | Notes |
|----------|---------|------|-------|
| **SMB/CIFS** | `jcifs-ng` (pure Java) | 445 | Windows/macOS/Linux native mount |
| **WebDAV** | `milton-webdav` or `sardine` | 8080 | HTTP-based, works over VPN, browser access |
| **FTP** | `apache-ftpserver` (embedded) | 21/2121 | Legacy compatibility, passive mode |

### Storage Abstraction
```kotlin
interface StorageProvider {
    val root: File
    fun list(path: String): List<FileEntry>
    fun openRead(path: String): InputStream
    fun openWrite(path: String, append: Boolean): OutputStream
    fun mkdir(path: String): Boolean
    fun delete(path: String): Boolean
    fun getFreeSpace(): Long
}
```
Implementations:
- `InternalStorageProvider` (app private dir)
- `ExternalStorageProvider` (SD card, USB OTG drives via `StorageVolume`)
- `UnifiedStorageProvider` (merges multiple roots under virtual `/storage`)

### Auth
- Single user/password (configurable in UI)
- Anonymous read-only toggle
- Per-share permissions (future)

### USB OTG Drive Handling
- `StorageVolume` list from `StorageManager`
- Auto-mount on `ACTION_MEDIA_MOUNTED`
- Expose each volume as separate SMB/WebDAV share

---

## 5. Webcam Streaming (`core-camera`)

### Camera2 Pipeline
```
CameraDevice → ImageReader (YUV_420_888) → Encoder → StreamServer
```

### Output Formats
| Format | Encoder | Clients | Latency |
|--------|---------|---------|---------|
| **MJPEG** | Custom (JPEG per frame) | Browser, VLC, ffmpeg, Home Assistant | ~100-200ms |
| **RTSP** | `libstreaming` / `rtsp-server-java` | VLC, FFmpeg, IP cameras, NVRs | ~200-500ms |
| **WebRTC** | `webrtc-android` (Google) | Browser, WebRTC clients | ~50-150ms |

### Implementation Priority
1. **MJPEG** — Simplest, universal client support, HTTP on port 8081
2. **RTSP** — Standard for surveillance/NVR integration
3. **WebRTC** — Lowest latency, browser-native, complex (ICE/STUN/TURN)

### Camera Features
- Resolution/bitrate presets (720p@15fps, 1080p@30fps, 4K@15fps)
- Torch toggle (flashlight mode)
- Focus mode (auto, fixed, macro)
- Recording to storage (MP4 via `MediaMuxer`) — optional, toggleable

### Power Optimization
- `CameraDevice.StateCallback` releases camera when service stopped
- Frame rate throttling when no clients connected
- `ImageReader` buffer count = 3 (minimize copies)

---

## 6. Power Management (`core-power`)

### Wake Locks
| Lock Type | When Held | Release |
|-----------|-----------|---------|
| `PARTIAL_WAKE_LOCK` | Any service RUNNING | All services STOPPED |
| `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) / `WIFI_MODE_FULL_HIGH_PERF` | Any network service RUNNING | All network services STOPPED |

### Battery Health (per game plan §6)
- **Charge limit** — If device supports `BatteryManager.BATTERY_PROP_CHARGE_COUNTER` or OEM API, cap at 80%
- **Thermal monitoring** — `ThermalManager` callbacks; throttle services at `THROTTLING_LIGHT`
- **Battery stats logging** — Periodic log: level, temperature, health, charging status

### 24/7 Operation
- `START_STICKY` foreground service with `FOREGROUND_SERVICE_DATA_SYNC`
- Auto-restart on crash (exponential backoff)
- Daily self-health check: disk space, memory, network, camera availability

---

## 7. Service Discovery (`core-discovery`)

### mDNS (Bonjour/Avahi)
- Advertise each enabled service with `_service._tcp.local.`
- TXT records: `version=1`, `path=/`, `auth=required`
- Library: `jmdns` (pure Java)

### SSDP/UPnP
- For Windows Network discovery
- `rootdevice.xml` with service list

### Static Fallback
- Simple HTTP endpoint `/services.json` with `{id, name, port, protocol, enabled}`

---

## 8. UI — Unified Dashboard (`app`)

### MainActivity Layout
```
┌─────────────────────────────────────┐
│  Android Home Server                │
│  [Status: RUNNING]  [IP: 192.168.x.x]│
├─────────────────────────────────────┤
│  ☐ Print Server        [●] Port 9100│
│  ☐ File Sharing        [●] SMB/WebDAV│
│  ☐ Webcam Stream       [○] MJPEG/RTSP│
│  ☐ Service Discovery   [●] mDNS/SSDP │
├─────────────────────────────────────┤
│  Storage: 12.3 GB / 58.7 GB free    │
│  Battery: 78% 🔋 32°C  (Charging)   │
│  Uptime: 3d 14h                     │
├─────────────────────────────────────┤
│  [Logs]  [Settings]  [Restart All]  │
└─────────────────────────────────────┘
```

### Settings Screens
- **File Sharing** — User/pass, anonymous toggle, share list (add/edit/remove paths)
- **Webcam** — Resolution, bitrate, format priority (MJPEG/RTSP/WebRTC), torch
- **Print** — Port, USB printer re-scan, job history
- **Power** — Charge limit (if supported), thermal throttle toggle
- **Network** — Bind interface (Wi-Fi only), port overrides
- **Advanced** — Log level, factory reset, export config

### Real-time Updates
- `StateFlow` per service → UI collects via `repeatOnLifecycle`
- WebSocket from `app` HTTP server for instant toggle feedback (optional)

---

## 9. Implementation Phases

### Phase 0 — Foundation (Week 1)
- [ ] Add new module Gradle configs (`core-files`, `core-camera`, `core-power`, `core-discovery`)
- [ ] `core-common`: `Service` interface, `ServiceState`, `DataStore` prefs
- [ ] `core-power`: WakeLock/WifiLock manager, battery health logger
- [ ] `ServerRepository` refactor: service registry, toggle persistence
- [ ] UI: Main screen with 4 toggle cards, status footer

### Phase 1 — File Sharing (Week 2)
- [ ] `core-files`: `StorageProvider` abstraction + implementations
- [ ] SMB server via `jcifs-ng` on port 445 (requires root? No — bind >1024, use 4445 + port forward or document)
- [ ] WebDAV server via `milton-webdav` on port 8080
- [ ] FTP server via `apache-ftpserver` on port 2121
- [ ] Auth config in UI, share management

### Phase 2 — Webcam MJPEG (Week 3)
- [ ] `core-camera`: Camera2 → `ImageReader` → MJPEG encoder
- [ ] HTTP MJPEG endpoint (`multipart/x-mixed-replace`) on 8081
- [ ] UI: resolution/bitrate presets, torch toggle
- [ ] Test with browser, VLC, Home Assistant

### Phase 3 — Webcam RTSP + WebRTC (Week 4)
- [ ] RTSP server via `rtsp-server-java` on 554/8554
- [ ] WebRTC signaling + ICE (STUN: `stun.l.google.com:19302`)
- [ ] H.264 hardware encoding via `MediaCodec`

### Phase 4 — Discovery & Polish (Week 5)
- [ ] `core-discovery`: mDNS (jmdns) + SSDP
- [ ] Service auto-advertisement on toggle
- [ ] Settings persistence, export/import
- [ ] Battery charge limit (OEM APIs: Samsung, Pixel, OnePlus)
- [ ] Thermal throttling integration
- [ ] 72h soak test

### Phase 5 — Hardening (Week 6)
- [ ] Crash recovery, watchdog
- [ ] Log rotation, diagnostic bundle export
- [ ] Security: per-service IP allowlist, TLS for WebDAV/HTTPS
- [ ] Documentation, README per service

---

## 10. Dependencies (New)

```kotlin
// core-files
implementation("org.samba.jcifs:jcifs-ng:2.1.12")        // SMB
implementation("io.milton:milton-server-ce:2.7.1")        // WebDAV
implementation("org.apache.ftpserver:ftpserver-core:1.2.0") // FTP

// core-camera
implementation("androidx.camera:camera-core:1.3.3")
implementation("androidx.camera:camera-camera2:1.3.3")
implementation("org.webrtc:google-webrtc:1.0.32006")      // WebRTC (heavy)
implementation("net.majorkernelpanic:streaming:3.0.1")    // RTSP/MJPEG (lighter)

// core-power
implementation("androidx.core:core-ktx:1.13.1")           // ThermalManager

// core-discovery
implementation("javax.jmdns:jmdns:3.5.6")                 // mDNS
```

**Note on APK size:** WebRTC adds ~8-10 MB. Make it a dynamic feature module or separate APK split if size is critical. MJPEG+RTSP via `streaming` library is ~1 MB.

---

## 11. Security Checklist

- [ ] All services bind to Wi-Fi interface only (not mobile data)
- [ ] Default credentials forced-change on first run
- [ ] TLS for WebDAV (self-signed cert, trust-on-first-use)
- [ ] FTP over TLS (FTPS) option
- [ ] mDNS only advertises on LAN
- [ ] No UPnP port forwarding
- [ ] Audit log for file access, print jobs, camera access

---

## 12. Definition of Done (v2.0)

- [ ] Phone boots → Home Server app starts → all enabled services RUNNING
- [ ] Windows `\\phone\share` mounts via SMB
- [ ] macOS/Linux `dav://phone:8080/share` mounts via WebDAV
- [ ] Browser `http://phone:8081/video.mjpeg` shows live camera
- [ ] VLC `rtsp://phone:554/cam` plays stream
- [ ] WebRTC demo page works in Chrome/Firefox
- [ ] All 4 services toggle independently, persist across reboot
- [ ] 72h continuous run: no crashes, battery stable, thermal controlled
- [ ] Phone screen off, charging at 80% limit (if supported)
- [ ] Diagnostic bundle export captures all logs, configs, stats

---

## 13. Android Background Execution Survival Guide

### The Problem
Android aggressively kills background processes. Since API 23 (Doze), API 26 (Background Limits), API 28 (App Standby Buckets), API 31 (Foreground Service Types), API 34 (Foreground Service Kills):

| Threat | Trigger | Mitigation |
|--------|---------|------------|
| **Doze Mode** | Screen off, stationary, no charger | `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` + `requestIgnoreBatteryOptimizations` |
| **App Standby Bucket** | Infrequent user interaction | Foreground service keeps bucket "active" |
| **Background Service Limits** (API 26+) | Service runs > few minutes without FG | **All long-running services MUST be foreground** |
| **FG Service Type Enforcement** (API 31+) | Wrong `foregroundServiceType` | Use `dataSync` + `mediaProjection` (camera) + `location` (if needed) |
| **FG Service Kill** (API 34+) | Long-running FG without user visibility | `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` + periodic notification updates |
| **OEM Killers** (Samsung, Xiaomi, OnePlus, etc.) | Aggressive battery savers | Whitelist via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + OEM-specific intents |
| **Process OOM Kill** | Memory pressure | Minimize heap, use `isLowRam` configs, avoid leaks |

### Survival Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Main Process (persistent)                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  HomeServerService (FG, DATA_SYNC + MEDIA_PROJECTION)   │   │
│  │  - Holds PARTIAL_WAKE_LOCK                               │   │
│  │  - Holds WIFI_LOCK (LOW_LATENCY)                         │   │
│  │  - Runs ServiceRegistry (starts/stops child services)   │   │
│  │  - Watchdog: restarts dead services, logs health         │   │
│  │  - Battery/Thermal monitor → throttles services         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│        ┌─────────────────────┼─────────────────────┐           │
│        ▼                     ▼                     ▼           │
│  ┌───────────┐         ┌───────────┐         ┌───────────┐    │
│  │ Print Svc │         │ File Svc  │         │Camera Svc │    │
│  │ (TCP 9100)│         │ (SMB/WebDAV)│       │ (MJPEG)   │    │
│  └───────────┘         └───────────┘         └───────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### Required Manifest Declarations
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" /> <!-- for watchdog -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".HomeServerService"
    android:foregroundServiceType="dataSync|mediaProjection"
    android:exported="false" />

<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.ACTION_POWER_CONNECTED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

### Battery Optimization Whitelist Flow
```kotlin
// On first run / settings screen
fun requestBatteryWhitelist(activity: Activity) {
    val pm = activity.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        activity.startActivityForResult(intent, REQ_IGNORE_BATTERY)
    }
}

// OEM-specific whitelists (best effort)
fun requestOemWhitelist(context: Context) {
    // Samsung: "com.samsung.android.lool" -> "Auto disable unused apps"
    // Xiaomi: "miui.securitycenter" -> "Battery saver" -> "App battery saver"
    // OnePlus: "com.oneplus.security" -> "Battery optimization"
    // Huawei: "com.huawei.systemmanager" -> "Protected apps"
    val oemIntents = listOf(
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent().setComponent(ComponentName("miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
    )
    oemIntents.forEach { intent ->
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
```

### WakeLock + WifiLock Manager (`core-power/PowerManager.kt`)
```kotlin
class PowerManager(private val context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var activeServices = 0
    private val lock = Any()

    fun acquire() = synchronized(lock) {
        activeServices++
        if (activeServices == 1) {
            wakeLock?.release()
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeServer:wake").apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiLock?.release()
            val mode = if (Build.VERSION.SDK_INT >= 29) 
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY 
            else 
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "HomeServer:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock+WifiLock acquired (services=$activeServices)")
        }
    }

    fun release() = synchronized(lock) {
        activeServices = max(0, activeServices - 1)
        if (activeServices == 0) {
            wakeLock?.release(); wakeLock = null
            wifiLock?.release(); wifiLock = null
            Log.i(TAG, "WakeLock+WifiLock released")
        }
    }
}
```

### Thermal Throttling (`core-power/ThermalMonitor.kt`)
```kotlin
class ThermalMonitor(private val context: Context, private val onThrottle: (Int) -> Unit) {
    private val thermal = context.getSystemService(ThermalManager::class.java)
    private var callback: ThermalManager.OnThermalStatusChangedListener? = null

    fun start() {
        callback = object : ThermalManager.OnThermalStatusChangedListener {
            override fun onThermalStatusChanged(status: ThermalStatus) {
                when (status.status) {
                    ThermalStatus.THROTTLING_NONE -> onThrottle(0)
                    ThermalStatus.THROTTLING_LIGHT -> onThrottle(1)   // Reduce camera fps, limit concurrent transfers
                    ThermalStatus.THROTTLING_MODERATE -> onThrottle(2) // Pause camera, limit file transfers
                    ThermalStatus.THROTTLING_SEVERE -> onThrottle(3)  // Stop all non-critical services
                    ThermalStatus.THROTTLING_CRITICAL -> onThrottle(4) // Emergency stop, only keep FG service alive
                }
            }
        }
        thermal.addThermalStatusListener(callback!!)
    }

    fun stop() { callback?.let { thermal.removeThermalStatusListener(it) } }
}
```

### Charge Limit / Bypass Charging (OEM APIs)
```kotlin
// Samsung: com.samsung.android.knox.restriction.BatteryRestrictionPolicy
// Pixel:   com.google.android.apps.battery.BatteryChargeLimit (hidden API)
// OnePlus: com.oneplus.battery.BatteryOptimization
// Generic: Check for "Battery protection" / "Charge limit" in Settings via intent

fun setChargeLimit(context: Context, limitPercent: Int): Boolean {
    return try {
        // Try hidden BatteryManager API (Pixel 6+)
        val bm = context.getSystemService(BatteryManager::class.java)
        val method = BatteryManager::class.java.getDeclaredMethod("setChargeLimit", Int::class.java)
        method.isAccessible = true
        method.invoke(bm, limitPercent)
        true
    } catch (_: Exception) {
        // Fallback: open OEM battery settings
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        false
    }
}
```

### Watchdog & Self-Healing
```kotlin
class Watchdog(private val serviceRegistry: ServiceRegistry) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    fun start() {
        scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // Every 5 min
                serviceRegistry.allServices.forEach { svc ->
                    if (svc.state.value == ServiceState.RUNNING && !svc.isHealthy()) {
                        Log.w(TAG, "${svc.id} unhealthy, restarting...")
                        svc.stop()
                        svc.start(context)
                    }
                }
                // Daily full health check
                if (shouldRunDailyCheck()) runDailyHealthCheck()
            }
        }
    }
    
    private fun runDailyHealthCheck() {
        // Disk space, memory, network, camera, battery, thermal
        // Log summary, alert if critical
    }
}
```

---

## 14. Battery & Heat Risk Mitigation (Hardware Level)

### Physical Setup (from Game Plan §6-7)
| Risk | Mitigation |
|------|------------|
| **Battery swelling/fire** | Remove battery entirely, run on USB power + bypass board; or use phone with "bypass charging" / "charge limit" (80%) |
| **Overheating** | Phone on vertical stand, passive cooling (aluminum heatsink on back), avoid direct sun, disable fast charging |
| **USB OTG + charging** | Powered OTG Y-cable: one leg to charger, one to drive/printer; test specific phone model |
| **Screen burn-in** | Screen OFF 100% (not dim); use `FLAG_KEEP_SCREEN_OFF` in FG service notification |

### Software Safeguards
- **Charge limit 80%** — Default on, user can disable (with warning)
- **Thermal throttle** — Auto-reduce camera FPS, pause file indexing, limit concurrent SMB connections
- **Battery health logging** — Daily CSV: timestamp, level, temp, voltage, health, charging, capacity_estimate
- **Emergency stop** — If temp > 45°C or battery health = DEAD/OVERHEAT, stop all services, keep only FG service alive

---

## 15. Recommended Additional Features

### Core Appliance Features
| Feature | Description | Module | Effort |
|---------|-------------|--------|--------|
| **Network Scanner** | Periodic ARP/nmap scan → device list with MAC vendor, open ports | `core-network` | Low |
| **Wake-on-LAN** | Send magic packets to wake PCs/NAS from UI | `core-network` | Low |
| **DDNS Updater** | Update Cloudflare/DuckDNS/No-IP when WAN IP changes | `core-network` | Low |
| **VPN Server** | WireGuard/Tailscale for remote access to home LAN | `core-network` | Medium |
| **Time Machine Target** | SMB share with `fruit:time machine` for macOS backups | `core-files` | Low |
| **Media Server** | DLNA/UPnP (MiniDLNA) + Jellyfin/Plex for media streaming | `core-files` | Medium |
| **Download Manager** | Aria2/qBittorrent-nox for torrents, yt-dlp for media | `core-files` | Medium |
| **Backup Agent** | Rsync/Restic/Borg to external drive or cloud (rclone) | `core-files` | Medium |

### Camera & Vision
| Feature | Description | Module | Effort |
|---------|-------------|--------|--------|
| **Motion Detection** | Frame diff → trigger recording, alert, Home Assistant webhook | `core-camera` | Medium |
| **Object Detection** | TensorFlow Lite (MobileNet-SSD, YOLOv8n) for person/car/package | `core-camera` | High |
| **Face Recognition** | Local face enrollment + recognition (privacy-first) | `core-camera` | High |
| **ANPR / License Plate** | OpenALPR / plate recognition for driveway camera | `core-camera` | High |
| **Timelapse** | Daily/hourly timelapse saved to storage | `core-camera` | Low |
| **Multi-camera** | Support USB UVC cameras via OTG + phone cameras | `core-camera` | Medium |

### Home Automation Integration
| Feature | Description | Module | Effort |
|---------|-------------|--------|--------|
| **Home Assistant MQTT** | Auto-discovery, sensor entities (battery, temp, storage, uptime) | `core-discovery` | Low |
| **Matter/Thread Border Router** | If phone has Thread radio (Pixel 8+, some Samsungs) | `core-discovery` | High |
| **IR Blaster** | Control AC/TV via IR (if phone has IR) | `core-hardware` | Low |
| **GPIO / USB Relay** | Control relays via OTG (sonoff, custom) | `core-hardware` | Medium |

### System & Monitoring
| Feature | Description | Module | Effort |
|---------|-------------|--------|--------|
| **Prometheus Exporter** | `/metrics` endpoint for Grafana dashboards | `core-common` | Low |
| **Grafana Dashboards** | Pre-built JSON for uptime, battery, network, storage, camera | `app` | Low |
| **Log Aggregation** | Loki/Graylog shipper or local Loki | `core-common` | Medium |
| **Auto-update** | Check GitHub releases, download APK, silent install (root) | `app` | Medium |
| **Kiosk / Launcher Mode** | Lock to Home Server app, disable status bar, auto-start | `app` | Medium |
| **Remote Wipe / Lock** | SMS/command trigger for theft | `app` | Low |

### Developer / Power User
| Feature | Description | Module | Effort |
|---------|-------------|--------|--------|
| **ADB over TCP** | Enable ADB on port 5555 for remote debugging | `app` | Low |
| **SSH Server** | Dropbear SSH for shell access (no Termux dependency) | `core-files` | Low |
| **Script Engine** | Run user scripts (Python via Chaquopy, JS via QuickJS) on events | `core-common` | High |

---

## 16. Updated Definition of Done (v2.1 - Hardened)

- [ ] **Process Survival**: `HomeServerService` restarts after: Doze, App Standby, OOM kill, crash, reboot, package update, power loss
- [ ] **Battery Whitelist**: User guided through `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + OEM-specific whitelists
- [ ] **Charge Limit**: 80% default enforced (or bypass charging if hardware supports)
- [ ] **Thermal Throttle**: Camera FPS drops at LIGHT, pauses at MODERATE, stops at SEVERE; file services rate-limited
- [ ] **WakeLock/WifiLock**: Held only while ≥1 service RUNNING; released cleanly
- [ ] **Watchdog**: 5-min service health check + daily full diagnostic
- [ ] **Boot Recovery**: `BootReceiver` starts FG service on `BOOT_COMPLETED`, `POWER_CONNECTED`, `MY_PACKAGE_REPLACED`
- [ ] **72h Soak**: No crashes, battery ≤80%, temp ≤40°C, all services responsive
- [ ] **Diagnostic Bundle**: One-tap export (logs, configs, metrics, battery history, thermal history, crash reports)

---

## 17. Immediate Next Steps (Updated)

1. **Phase 0.1** — Add `core-power` module with `PowerManager`, `ThermalMonitor`, `BatteryHealthLogger`, `Watchdog`
2. **Phase 0.2** — Refactor `ServerRepository` → `ServiceRegistry` with `Service` lifecycle
3. **Phase 0.3** — `HomeServerService` (FG) integrating PowerManager + ServiceRegistry + Watchdog
4. **Phase 0.4** — Battery whitelist flow + OEM intents in Settings UI
5. **Phase 0.5** — MainActivity toggle cards bound to `ServiceRegistry` StateFlows
6. **Phase 1+** — File Sharing, Camera, Discovery as before

This ensures the "24/7 appliance" requirement is baked into the architecture from day one, not bolted on later.