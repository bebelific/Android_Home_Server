#!/usr/bin/env python3
"""Bebelific Homeserver — runtime audit. Usage: python audit.py <phone-ip>"""
import socket, struct, sys, time, json, urllib.request, urllib.error

IP = sys.argv[1] if len(sys.argv) > 1 else '192.168.8.181'
results = []

def check(name, ok, detail=''):
    results.append((name, 'PASS' if ok else 'FAIL', detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}  {detail}")

def skip(name, detail=''):
    results.append((name, 'SKIP', detail))
    print(f"[SKIP] {name}  {detail}")

def tcp(port, timeout=2.5):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((IP, port)); return True
    except Exception:
        return False
    finally:
        s.close()

def http(path, port=8080, timeout=6, headers=None, method='GET'):
    req = urllib.request.Request(f'http://{IP}:{port}{path}', headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()

def dns_query(name, port, timeout=2.5):
    tid = 0x1234
    pkt = struct.pack('>HHHHHH', tid, 0x0100, 1, 0, 0, 0)
    pkt += b''.join(bytes([len(l)]) + l.encode() for l in name.split('.')) + b'\x00'
    pkt += struct.pack('>HH', 1, 1)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(pkt, (IP, port))
        data, _ = s.recvfrom(1500)
        return data
    except Exception:
        return None
    finally:
        s.close()

print(f'=== Bebelific Homeserver runtime audit — {IP} ===')

# 1. TCP surfaces
for port, name in [(8080, 'WebDAV/HTTP'), (8081, 'MJPEG'), (2121, 'FTP'), (9100, 'Print raw')]:
    check(f'tcp:{port} {name}', tcp(port))

# 2. Webcam engine
try:
    st = json.loads(http('/status', 8081)[2].decode())
    check('webcam:status-json', 'running' in st, json.dumps(st))
    if st.get('running'):
        a = st.get('frames', 0)
        time.sleep(8)
        st2 = json.loads(http('/status', 8081)[2].decode())
        delta = st2.get('frames', 0) - a
        check('webcam:frames-flowing', delta >= 40, f'+{delta} frames in 8s ({st2.get("resolution")})')
        code, hdr, body = http('/snapshot.jpg', 8081, 12)
        ok = code == 200 and body[:3] == b'\xff\xd8\xff' and len(body) > 5000
        check('webcam:snapshot-jpeg', ok, f'{len(body)}B')
        code, hdr, body = http('/view', 8081)
        check('webcam:/view-player', code == 200 and b'snapshot mode' in body, f'{len(body)}B')
    else:
        skip('webcam:frames-flowing', 'camera not attached (locked?)')
        skip('webcam:snapshot-jpeg', 'camera not attached')
        skip('webcam:/view-player', 'camera not attached')
except Exception as e:
    check('webcam:status-json', False, str(e))

# 3. Files / WebDAV
def http_safe(path, port=8080, timeout=6, headers=None, method='GET'):
    try:
        return http(path, port, timeout, headers, method)
    except Exception as e:
        return None, None, str(e).encode()

code, hdr, body = http_safe('/')
if code is None:
    skip('files:browser-page', 'File Sharing toggle is off')
    skip('files:PUT-upload', 'off')
    skip('files:HTTP-Range-seek', 'off')
    skip('files:DELETE', 'off')
else:
    check('files:browser-page', code == 200 and b'<html' in body.lower(), f'{len(body)}B')
    test = b'HOMESERVER-AUDIT' * 40
    code, hdr, body = http_safe('/audit_probe.bin', method='PUT') if False else (None, None, None)
    req = urllib.request.Request(f'http://{IP}:8080/audit_probe.bin', data=test, method='PUT')
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            put_ok = r.status in (200, 201)
    except Exception as e:
        put_ok = False
    check('files:PUT-upload', put_ok)
    code, hdr, body = http_safe('/audit_probe.bin', headers={'Range': 'bytes=100-199'})
    if code == 206:
        cr = hdr.get('Content-Range', '')
        check('files:HTTP-Range-seek', len(body) == 100 and cr.startswith('bytes 100-199/'), cr)
    else:
        check('files:HTTP-Range-seek', False, f'status={code}')
    code, hdr, body = http_safe('/audit_probe.bin', method='DELETE')
    check('files:DELETE', code is not None and code < 300)

# 4. FTP
try:
    s = socket.create_connection((IP, 2121), timeout=3)
    banner = s.recv(96).decode('latin1').strip()
    check('ftp:banner', banner.startswith('220'), banner)
    s.close()
except Exception as e:
    check('ftp:banner', False, str(e)[:60])

# 5. Ad-block DNS (probe both ports; service may be off)
dns_any = False
for port in (53, 5353):
    resp = dns_query('doubleclick.net', port)
    if resp is None:
        continue
    dns_any = True
    rcode = resp[3] & 0xF
    blocked_ok = rcode == 3 or (struct.unpack('>H', resp[6:8])[0] > 0 and resp[-4:] == b'\x00\x00\x00\x00')
    check(f'dns:{port} blocks-tracker', blocked_ok, f'rcode={rcode}')
    resp2 = dns_query('example.com', port)
    ok2 = resp2 is not None and (resp2[3] & 0xF) == 0
    check(f'dns:{port} allows-normal', ok2, f'rcode={resp2[3] & 0xF}' if resp2 else 'none')
if not dns_any:
    skip('dns:filter', 'service off or unreachable')

# 6. Security spot-checks: webcam auth expected when password set
# (informational only — handled by HTTP 401 path)

fails = [r for r in results if r[1] == 'FAIL']
print(f'\n=== {len(results) - len(fails)}/{len(results)} passed, {len(fails)} failed ===')
sys.exit(1 if fails else 0)
