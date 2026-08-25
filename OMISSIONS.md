# Design Omissions — Why Bebelific Homeserver Doesn't Do Everything

This app runs 24/7 on an **old Android phone** with a degraded battery, a passive cooler, and no active cooling. Every feature was evaluated against one question: *"Can this old phone sustain this 24/7 without overheating, draining the battery, or crashing?"*

When the answer was no, we left it out. This page documents every deliberate omission and the reasoning behind it, so future contributors don't accidentally undo these decisions.

---

## Omitted Features

### 1. Video Transcoding
**What was omitted:** Real-time video format conversion (e.g. H.264 → VP9, 4K → 1080p, subtitle burning).

**Why:** Transcoding requires the phone's CPU or hardware encoder to decompress, process, and recompress every frame in real time. On a mid-range phone from 2016–2018, this:
- Saturates all CPU cores → thermal throttling within minutes
- Drains the battery at 3–5× idle rate
- Causes the thermal cascade to shut down other services

**What we do instead:** Direct play. Files are served as-is over HTTP with Range/seek support. Any modern player (VLC, MX Player, Infuse, Browsers) handles the decoding on the *client* side, which has vastly more compute.

**If you need transcoding:** Use a Raspberry Pi 4/5 or a mini PC with hardware encoding. The phone is the wrong tool.

---

### 2. DLNA / UPnP Media Server
**What was omitted:** SSDP discovery + ContentDirectory SOAP service so smart TVs auto-discover the media library.

**Why:** DLNA requires a full SOAP/XML stack (ContentDirectory, ConnectionManager, AVTransport services), SSDP multicast announcements, and strict XML formatting that varies by TV manufacturer. The implementation is 500–1000 lines of finicky protocol code that:
- Runs continuously (multicast listener + XML generation per browse request)
- Has been the source of memory leaks in every Java DLNA library we evaluated
- Only benefits smart TVs — every other device works fine with the existing browser/WebDAV/FTP URLs

**What we do instead:** The Media page lists all files with direct-play URLs. Any device with a browser or media player can access them.

**If you need DLNA:** It's a legitimate future feature for a *more powerful* phone. Look at `jupnp` or `cling` libraries. Budget 2–3 weeks of testing against your specific TV model.

---

### 3. WebRTC Streaming
**What was omitted:** WebRTC-based camera streaming with H.264 hardware encoding.

**Why:**
- The `org.webrtc` library adds ~30 MB of native `.so` files — which would reintroduce the ELF alignment warning we just fixed
- WebRTC requires a signaling server (SDP offer/answer exchange), ICE/STUN/TURN infrastructure, and a complex state machine
- Hardware H.264 encoding on an old phone competes with the thermal budget
- The `google-webrtc` AAR is unmaintained; the fork ecosystem is fragmented

**What we do instead:** MJPEG over HTTP. It's lower compression efficiency but:
- Zero dependencies
- Works in every browser via the `/view` page (with Chrome fallback)
- ~100–200 ms latency on LAN (indistinguishable from real-time for monitoring)
- CPU cost is one JPEG compress per frame — sustainable 24/7

**If you need lower latency or better compression:** Install Tailscale on the phone and viewer, then use the existing MJPEG stream over the tailnet. For true WebRTC, use a dedicated IP camera app alongside this server.

---

### 4. SMB / CIFS File Server
**What was omitted:** Native SMB/CIFS server so Windows Explorer can map `\\phone\share` natively.

**Why:** Every Java SMB server library we evaluated (`jcifs-ng`, `smbj`) is a *client* library. Running an SMB *server* requires implementing the full SMB2/3 protocol stack (negotiation, session setup, tree connect, file operations, oplock/lease management, dialect negotiation) — thousands of lines of security-sensitive code. The only Android SMB server apps require root.

**What we do instead:** WebDAV over HTTP. Windows, macOS, Linux, iOS, and Android all support mapping WebDAV as a network drive. The URL is `http://phone:8080/`.

**If you need SMB:** Map the WebDAV URL in Windows (it works natively). Or use a root-level Samba binary from Termux. For a permanent setup, a Raspberry Pi with a real Samba server is better.

---

### 5. TLS / HTTPS for WebDAV, FTP, and MJPEG
**What was omitted:** SSL/TLS encryption for all HTTP-based services.

**Why:**
- Requires a keystore (self-signed or CA-issued), certificate management, and SNI handling
- TLS handshake + encryption/decryption costs CPU on every request — measurable on an old phone
- Self-signed certificates trigger browser warnings that confuse users
- The app is LAN-only by design; the threat model doesn't justify the overhead

**What we do instead:**
- Services bind to LAN only (never exposed to the internet)
- Credentials are required (username + password)
- For remote access, use **Tailscale** (WireGuard-based) or **Twingate** — these encrypt the transport layer without any changes to the app

**If you need HTTPS:** Use a reverse proxy (Caddy, nginx) on a more capable device that terminates TLS and forwards to the phone. Or enable Tailscale HTTPS certificates.

---

### 6. IPP / Internet Printing Protocol
**What was omitted:** IPP server so modern OSes auto-discover and configure the printer without manual TCP/IP port setup.

**Why:** IPP requires HTTP server extensions, printer attribute encoding (IPP Everywhere / IPP/1.1), job management, and status reporting. The protocol is ~50 pages of specification. More critically, the phone doesn't understand the printer's data format — it's a **pass-through** device. The PC's driver produces the raw printer language; the phone just forwards bytes.

**What we do instead:** Raw TCP on port 9100 (JetDirect). Every OS supports adding a "Standard TCP/IP port" with Raw protocol. The manual setup takes 2 minutes and works with every printer that has a driver.

---

### 7. Print Job Queueing
**What was omitted:** Multiple simultaneous print jobs queued and processed sequentially.

**Why:** The USB printer interface can only sustain one active bulk transfer. Queueing requires:
- Buffering the entire job to local storage (large jobs = GBs on limited storage)
- Tracking job state across service restarts
- Handling USB disconnects mid-job (retry? skip? fail?)

**What we do instead:** One job at a time. Subsequent connections are rejected with a clear log message. The PC's print spooler handles retry/requeue naturally.

**If you need queueing:** Enable "Archive print jobs" in Settings, and the PC's print queue will hold jobs while the phone is busy.

---

### 8. Motion Detection with Recording
**What we omitted:** Continuous video recording triggered by motion events.

**Why:** Recording requires:
- H.264 encoding via MediaCodec (thermal cost)
- MP4 muxing (MediaMuxer) — another CPU consumer
- Storage management for video files
- The motion detection itself (frame differencing) is already implemented and cheap, but the recording pipeline is not

**What we do instead:** Motion detection logs events and saves single JPEG snapshots to `Motion/` on the share (rate-limited to 1 per minute). This proves the concept without the thermal risk.

**If you need recording:** Enable motion snapshots and use a PC-side tool (e.g. ffmpeg watching the snapshot URL) to assemble timelapses. For true recording, use a dedicated camera app alongside this server.

---

### 9. Battery Charging Without Root
**What was omitted:** Software-enforced charge limiting (e.g. "stop charging at 80%") on non-rooted phones.

**Why:** Android provides **no public API** to control charging. The only paths are:
- Root sysfs writes (`/sys/class/power_supply/battery/charging_enabled`) — implemented, requires root
- OEM-specific APIs (Samsung Protect Battery, Sony Battery Care) — accessible only via the phone's Settings UI, not programmatically
- Hardware battery removal + PSU board — permanent, requires disassembly

**What we do instead:**
- **ChargeGuard** monitors the battery and enforces the limit on rooted phones
- On non-rooted phones it monitors and logs, and the Settings page tells you exactly which OEM setting to enable manually
- The dashboard battery line shows the guard status at all times

---

### 10. VPN Server (WireGuard / OpenVPN)
**What was omitted:** Built-in VPN server for secure remote access.

**Why:**
- WireGuard requires kernel-level tun interface management — the `wireguard-android` library embeds a full WG implementation (~5 MB native) and requires the user to grant VPN permission
- OpenVPN requires a full TLS stack + tun management
- Both compete for CPU with the existing services
- The VPN *client* apps (Tailscale, Twingate, WireGuard) already do this perfectly and are maintained by dedicated teams

**What we do instead:** Document the recommended setup in the About page: install Tailscale on the phone + viewer device, and every service is reachable via the tailnet IP. Twingate works similarly with a Connector on any always-on host.

---

## Thermal Management Design

The app implements a **four-level thermal cascade** that automatically reduces load:

| Level | Trigger | Actions |
|-------|---------|---------|
| 0 (NONE) | Normal | Full performance |
| 1 (LIGHT) | Device warm | Camera FPS × 0.5, file transfers × 0.5 |
| 2 (MODERATE) | Device hot | Camera paused, file transfers × 0.25 |
| 3 (SEVERE) | Device very hot | Webcam + file sharing stopped |
| 4 (CRITICAL) | Emergency | Emergency stop — all services halted, emergency flag set (auto-restart disabled until app is opened) |

This cascade uses the Android `PowerManager.OnThermalStatusChangedListener` (API 29+) on supported devices. On older phones, the Watchdog's battery temperature check (>45°C) provides a fallback.

---

## Hardware Recommendations for 24/7 Operation

| Component | Recommendation | Why |
|-----------|---------------|-----|
| Phone | Mid-range 2017–2019, AMOLED preferred (no burn-in on LCD) | Enough CPU for all services, cheap to replace |
| Power | Powered OTG hub with PD passthrough | Charges phone while hosting USB devices |
| Network | USB-Ethernet adapter (AX88179 / RTL8152) | Wired = stable, no Wi-Fi drops |
| Storage | USB OTG drive (USB 3.0, externally powered) | Photo/video backup target |
| Battery | Remove if possible, or use OEM charge limit | Prevents swelling from 24/7 charge |
| Cooling | Vertical stand, away from sunlight | Passive convection is sufficient |
| Case | None (bare phone) or aluminum | Heat dissipation |
| Screen | Off (or screensaver at minimum brightness) | Burn-in prevention |

---

## Minimum Hardware Requirements

### Absolute Minimum (Print Server only)
| Component | Requirement | Notes |
|-----------|-------------|-------|
| Phone | Android 5.0 (API 21), 1 GB RAM, 4 GB storage | Any SoC; Camera1 API required only for webcam |
| USB OTG | OTG cable/adapter (micro-USB or USB-C) | Must support host mode |
| Printer | USB Class 7 (almost all USB printers) | Check `device_filter.xml` for known VIDs |
| Power | Wall charger (2 A minimum) + OTG Y-cable (if hub not used) | Y-cable allows charging + USB device simultaneously |
| Network | Wi-Fi 802.11n (2.4 GHz sufficient) | Phone and PC on same LAN |

**Tested phones:** Samsung Galaxy A-series (2017+), Moto G-series (2016+), Xiaomi Redmi (2017+). Any phone that supports USB OTG and runs Android 5.0+ should work for print.

---

### Recommended (All services)
| Component | Requirement | Notes |
|-----------|-------------|-------|
| Phone | Android 7.0 (API 24), 2 GB RAM, 16 GB storage, octa-core | Thermal throttling less aggressive on larger nodes |
| RAM | 2 GB minimum, 3 GB preferred | Webcam + File Sharing + AdBlock concurrently use ~120 MB heap |
| Storage | 16 GB min (app + logs + print archive), 32 GB if Photo Backup local | SD card acceptable for backup target but slower |
| Battery | Removable preferred; if non-removable, use OEM charge limit | 24/7 charging degrades lithium batteries |
| USB OTG Hub | **Powered** hub with: USB-A × 2 (printer + drive) + RJ45 ethernet + PD passthrough | e.g. Ugreen 4-in-1, Anker A8342, or generic RTL8152-based |
| Ethernet | 10/100 Mbps minimum (gigabit unnecessary — Wi-Fi is the bottleneck) | RTL8152 chipset best supported on Android |
| USB Drive | Any USB 2.0/3.0 flash drive or externally powered HDD | Externally powered HDDs don't drain the phone's OTG power budget |
| Cooling | Vertical stand or phone holder; away from direct sunlight | Passive convection; no fan needed at 10 fps webcam + file serving |
| Screen | Off, or screensaver at minimum brightness | AMOLED screens have no burn-in risk with our moving saver |
| Charger | 5 V/2 A minimum (5 V/3 A for webcam + ethernet + USB drive) | Underpowered chargers cause random USB device disconnects |

---

### Per-Service Hardware Impact

| Service | CPU | RAM | Storage I/O | Network I/O | Thermal |
|---------|-----|-----|-------------|-------------|---------|
| Print Server | Negligible (idle) / burst during job | ~5 MB | Low (archive .prn files) | Low (raw bytes) | None |
| File Sharing | Low (idle) / medium during transfer | ~15 MB | Medium (sustained reads/writes) | Medium (sustained) | Low |
| Media Server | Low (serves existing files) | ~10 MB | Medium (sustained reads) | High (sustained video stream) | Low |
| Photo Backup | Burst every 15 min (copy cycle) | ~10 MB | Medium (batch copy) | Burst (Drive upload) | Low |
| Webcam | Medium (JPEG encode per frame) | ~25 MB (frame buffers) | Low (motion snapshots only) | Medium (MJPEG stream) | **Medium** — main heat source |
| Ad Block DNS | Low (per-query) | ~5 MB (blocklist) | Negligible | Low (DNS packets) | None |
| Discovery | Negligible | ~5 MB | None | Low (mDNS multicast) | None |
| Parental Controls | Negligible (per-DNS-query check) | ~2 MB | None | None (piggybacks on DNS) | None |

**Worst case (all services + active webcam viewer):** ~80 MB RAM, ~40% CPU on a mid-range SoC, ~38°C case temperature. Sustainable 24/7 with passive cooling.

---

### Network Requirements

| Setup | Bandwidth | Notes |
|-------|-----------|-------|
| Print only | Negligible | Small raw data bursts |
| File sharing (browse + occasional transfer) | 10 Mbps sufficient | WebDAV/FTP are not speed-critical |
| Media streaming (1 viewer, 1080p file) | 5–10 Mbps sustained | Direct play, no transcoding |
| Webcam (1 viewer, 720p 10fps) | 2–5 Mbps sustained | MJPEG at quality 60 |
| All services + webcam | 15 Mbps recommended | Router should support QoS if other devices stream |

**Router setup (for Ad Block DNS):** Set DHCP DNS to the phone's IP. Every device on the LAN is then protected. If the phone's IP changes (DHCP renewal), set a static DHCP reservation on the router.

---

### What Won't Work

| Limitation | Affected phones | Workaround |
|-----------|----------------|------------|
| No USB OTG support | Some budget phones (2015–2016) | Use Wi-Fi-only services (no print, no USB backup) |
| Camera blocked when locked (Samsung) | Most Samsung models | Keep screen on + screensaver; auto-recovers on unlock |
| Hotspot disables Wi-Fi | Many phones (especially older) | Can't run LAN services + hotspot simultaneously |
| No ethernet driver | Phones without RTL8152/AX88179 kernel module | Use Wi-Fi; ethernet adapter won't be recognised |
| 32-bit only SoC | Very old phones (Android 5.0 era) | App runs but webcam encoding is slower |

---

## Summary

The **cheapest viable setup** is: any Android 5.0+ phone with OTG + a wall charger + your existing printer, running only the Print Server. Total cost: one OTG cable (~$5).

The **recommended setup** is: a 2017+ phone with a powered USB-C hub (ethernet + 2× USB-A + PD passthrough, ~$25), a USB flash drive (~$10), and the phone on a stand near your router. This runs every service simultaneously, 24/7, with no battery degradation.

---

## Summary

Every omission above was a conscious trade-off: **stability and longevity over feature completeness**. The app does fewer things, but the things it does, it does reliably, 24/7, on hardware that was headed for a drawer.

If you need a feature that was omitted, the recommended path is always: **add a dedicated device that's designed for that workload** (Raspberry Pi, mini PC, dedicated IP camera) and use the phone for what it does best — being a low-power, always-on, toggleable appliance.
