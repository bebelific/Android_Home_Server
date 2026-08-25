# AndroidHomeServer

Turn an old Android phone (5.0+) into a **24/7 LAN home server**: network print server, file sharing, live webcam, and zero-config discovery — each independently toggleable, surviving reboots and Doze.

Philosophy: the phone is a **transport/appliance**, not a renderer. Your PC's drivers do the heavy lifting; the phone forwards, serves, and streams.

---

## Services & Ports (all toggleable, all LAN-only)

| Service | Protocol | Default port | Endpoint |
|---------|----------|--------------|----------|
| Print Server | Raw TCP (JetDirect) | `9100` | any TCP stream → USB printer |
| File Sharing | HTTP browser + WebDAV | `8080` | `http://<phone>:8080/` |
| File Sharing | FTP | `2121` | `ftp://<phone>:2121/` |
| Webcam | MJPEG over HTTP | `8081` | `/stream`, `/snapshot.jpg`, `/status` |
| Discovery | mDNS/Bonjour | `5353` | `_http._tcp`, `_ftp._tcp`, `_pdl-datastream._tcp` |

All ports are configurable in-app (Settings). Changes apply after **Restart all**.

---

## Requirements

- Android 5.0+ (API 21), ARM device with USB-OTG (for printing) and/or camera
- Wi-Fi on the same LAN as your clients
- Powered charger (a permanently-plugged appliance)

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 8.5.2 · Kotlin 1.9.24 · JDK 17 · compileSdk 34 · **zero native libs** (no ELF/16 KB-alignment issues).

Install & first run:

```bash
adb install -r app-debug.apk
```

1. Open **AndroidHomeServer** → grant Camera + Notifications permissions.
2. Accept the *battery-optimization exemption* prompt (and use Settings → *Open OEM battery panel* on Samsung/Xiaomi/etc.).
3. Toggle services ON. Toggles persist across reboot; the app auto-starts on boot / power-connect / app-update.

> **Note:** on Android 14 the camera can only attach after the app has been opened once post-reboot (OS rule for background camera). The webcam service keeps retrying every 10 s until then — everything else runs headless from boot.

---

## Printing (PC → Wi-Fi → phone → USB OTG → printer)

Pass-through design: the phone never parses the document. Your PC's printer driver produces the printer's native language; the phone streams it to the USB bulk endpoint (printer-class interface auto-detected, permission requested on first use).

**Windows setup:**
1. Install your printer's normal driver.
2. `Printers & Scanners → Add device → Add manually → Standard TCP/IP Port`
3. Device IP = phone IP, **Port = 9100, Protocol = Raw**.

Tap *Grant USB printer permission* in the app when it appears. One job prints at a time; job history (bytes in/out) is visible in the log tail.

## File sharing

- **Browser:** open `http://<phone>:8080/` — upload, download, mkdir, delete.
- **WebDAV:** map a drive to `http://<phone>:8080/` (Windows: *Map network drive*, RaiDrive/Cyberduck also work).
- **FTP:** any client to `ftp://<phone>:2121/`.

Files live under shared storage `/HomeServer/` (app-private fallback if storage is restricted). Credentials (Settings → Access): default user `homeserver`, empty password = open access; setting a password protects both WebDAV and FTP.

## Webcam

- Live stream (multi-viewer, up to 8): `http://<phone>:8081/stream`
- Snapshot (NVR-poll friendly): `http://<phone>:8081/snapshot.jpg`
- Telemetry JSON: `http://<phone>:8081/status`

Torch, front/back camera, FPS (5–30), JPEG quality are persisted settings. Thermal throttling automatically reduces FPS before pausing the camera.

---

## Reliability (the 24/7 part)

- Foreground service (`dataSync|camera`), `START_STICKY`, wake+wifi locks while ≥1 service runs
- Auto-start: `BOOT_COMPLETED` / `POWER_CONNECTED` / `MY_PACKAGE_REPLACED`
- Watchdog: unhealthy services restarted every 60 s; disk/heap/battery deep-check every 6 h
- Thermal cascade: LIGHT → halve FPS/transfers · MODERATE → pause cam, quarter transfers · SEVERE → stop non-critical · CRITICAL → emergency stop
- Battery health CSV (`battery_health.csv`, 15-min samples) in app storage
- **Charging guard**: set a charge limit (default 80%). On **rooted** phones the app pauses charging at the limit and resumes 15% lower (sysfs). Without root it monitors and logs only — true bypass charging is brand-specific:

  | Brand | Built-in option |
  |-------|-----------------|
  | Samsung | Battery → *Protect battery* (85%) |
  | Sony | *Battery care* / charge cap |
  | OnePlus / Oppo / Realme | *Optimized charging* |
  | ASUS ROG / RedMagic | *Bypass charging* (true DC passthrough) |
  | Xiaomi / POCO | Battery → *Battery protection* |

  Rooted alternative: [ACC (Advanced Charging Controller)](https://github.com/MatteCarra/AccA). Permanent appliance: battery-removal + PSU board (zero battery risk).

Full engineering plan: [`BUILD_PLAN.md`](BUILD_PLAN.md).

## Troubleshooting

```bash
adb install -r app-debug.apk                 # reinstall
adb logcat -s HomeServer TcpPrint FtpShare WebDav MjpegServer Discovery CamStreamer
adb shell run-as com.printserver.app cat files/print_server.log
```

- **Printer missing** → cable/OTG, tap *Grant USB printer permission*, check device_filter (USB class 7 or known VID).
- **Can't connect at all** → phone asleep on some OEMs: disable "Wi-Fi off during sleep"; confirm same subnet.
- **Factory reset**: Settings → *Factory reset settings*.

## Roadmap / known gaps

- RTSP/WebRTC out (MJPEG + snapshot today); SMB server N/A (client libs only — use WebDAV); charge-limit slider stores intent, enforcement depends on OEM API.

---
AGPL-friendly hobby hardware rescue. PRs welcome.
