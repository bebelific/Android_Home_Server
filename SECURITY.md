# Security Model — Bebelific Homeserver

## Threat Model

This is a **LAN-only appliance**. It never exposes services to the internet. The security model assumes:

- **Trusted network**: Your home Wi-Fi with WPA2/WPA3 encryption
- **Untrusted LAN clients**: Guest devices, IoT devices, or compromised devices on the same network
- **No remote access** unless you add Tailscale/Twingate (which provides its own encryption)

If you need internet-exposed access, **do not port-forward** — use Tailscale (WireGuard-based, zero config).

---

## Current Protections

| Layer | Protection | Status |
|-------|-----------|--------|
| Network binding | All services bind to LAN only (0.0.0.0 on Wi-Fi/ethernet) | ✅ Always on |
| WebDAV/Files | Username + SHA-256 password (HTTP Basic) | ✅ When password is set |
| Webcam MJPEG | Same credentials as File Sharing | ✅ When password is set |
| FTP | Username + SHA-256 password | ✅ When password is set |
| Path traversal | WebDAV resolves and validates every path against share root | ✅ Always on |
| USB printer | Android USB permission dialog required | ✅ Always on |
| Print data | Pass-through only — phone never interprets printer data | ✅ By design |
| Ad Block DNS | Filters queries; no auth needed (Pi-hole model) | ✅ By design |
| Discovery | mDNS advertises service names/ports only, no credentials | ✅ Informational |
| Gateway Mode | iptables rules require root; IPs validated before shell exec | ✅ When root available |
| Emergency stop | Thermal critical halts all services; auto-restart disabled until app opened | ✅ Always on |

---

## Known Weaknesses

| Weakness | Impact | Mitigation |
|----------|--------|-----------|
| **No password = open access** | Anyone on the LAN can read/write files, view camera | Set a password in Settings → Access |
| **SHA-256 unsalted hash** | Hash capture enables offline brute force | Use a strong password (12+ chars); LAN-only reduces exposure |
| **HTTP Basic over cleartext** | Credentials visible to LAN packet sniffer | Use Tailscale for encrypted transport |
| **FTP sends credentials in cleartext** | Same as above | Use WebDAV instead of FTP |
| **No rate limiting on auth failures** | Brute force possible from LAN | Strong password mitigates; Tailscale eliminates |
| **No per-service IP allowlist** | Any LAN device can access any service | Router-level firewall rules |
| **mDNS advertises service names** | Information disclosure to LAN | Disable Discovery toggle if not needed |
| **DNS filter has no auth** | Any LAN device can use it (by design, Pi-hole model) | Not a weakness — it's the intended behavior |

---

## Recommendations

### Minimum (do this now)
1. **Set a password** in Settings → Access (this protects Files, Webcam, and FTP)
2. **Use a strong password** (12+ characters, mixed case + numbers + symbols)
3. **Verify your Wi-Fi uses WPA2 or WPA3** (not WEP or open)

### Recommended (for always-on appliance)
4. **Install Tailscale** on the phone and your devices for encrypted remote access
5. **Disable FTP** if you don't need it (WebDAV is more secure)
6. **Set a static DHCP reservation** on your router so the phone's IP doesn't change
7. **Disable Discovery** after initial setup if you don't need mDNS

### Paranoid (if other people use your Wi-Fi)
8. **Put the phone on a separate VLAN** isolated from guest devices
9. **Use router firewall rules** to restrict which devices can reach which ports
10. **Check the log file** regularly for unexpected connections (all client IPs are logged)

---

## What We Don't Protect Against

| Attack | Why not | What to do |
|--------|---------|-----------|
| Physical theft of the phone | Full access to storage and running services | Use full-disk encryption (enabled by default on Android 7+) |
| Root-level compromise of the phone | Complete control over all services | Don't root the phone unless you need ChargeGuard |
| ARP spoofing / MITM on LAN | No TLS; cleartext HTTP is interceptable | Use Tailscale (WireGuard encryption) |
| Zero-day in Android USB stack | USB host attack surface | Don't plug in unknown USB devices |
| DoS by flooding a service port | No rate limiting on connections | Router-level firewall or VLAN isolation |
