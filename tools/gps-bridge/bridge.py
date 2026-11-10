#!/usr/bin/env python3
"""
GPS bridge: ส่ง GPS จริงจาก iPhone เข้า Android Emulator บน Mac

iPhone เปิดหน้าเว็บนี้ (ผ่าน HTTPS ของ Cloudflare Tunnel) → ตำแหน่งถูกส่งมาที่ /fix
→ สั่ง `adb emu geo fix` → แอป RunTrail ใน emulator ได้ตำแหน่งเหมือนวิ่งจริง

ใช้: python3 tools/gps-bridge/bridge.py   แล้วรัน cloudflared tunnel --url http://localhost:8765
"""
import json
import os
import secrets
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("BRIDGE_PORT", "8765"))
ADB = os.environ.get("ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
TOKEN = os.environ.get("BRIDGE_TOKEN") or secrets.token_urlsafe(8)  # กันคนอื่นที่รู้ลิงก์มาสั่งตำแหน่ง
MAX_ACCURACY_M = 30.0   # จุดที่แม่นยำแย่กว่านี้ไม่ส่ง (แอปก็ตัดที่ 25 ม. อยู่แล้ว)
PAGE = (Path(__file__).parent / "index.html").read_text(encoding="utf-8")

state = {"count": 0, "skipped": 0, "last": None, "last_at": 0.0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # พิมพ์เฉพาะจุดที่ส่ง (ดูด้านล่าง)

    def _send(self, code, body, ctype="application/json"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        q = self.path.split("?", 1)[1] if "?" in self.path else ""
        params = dict(p.split("=", 1) for p in q.split("&") if "=" in p)
        return secrets.compare_digest(params.get("k", ""), TOKEN) or \
            secrets.compare_digest(self.headers.get("X-Bridge-Token", ""), TOKEN)

    def do_GET(self):
        if not self._authorized():
            return self._send(403, "ลิงก์ไม่ถูกต้อง (ใช้ลิงก์ที่มี ?k=... ตามที่แสดงในเทอร์มินัล)", "text/plain; charset=utf-8")
        if self.path.startswith("/status"):
            return self._send(200, json.dumps(state))
        return self._send(200, PAGE, "text/html; charset=utf-8")

    def do_POST(self):
        if not self.path.startswith("/fix") or not self._authorized():
            return self._send(403, '{"error":"forbidden"}')
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 2048:
                return self._send(413, '{"error":"too large"}')
            p = json.loads(self.rfile.read(n))
            lat, lng = float(p["lat"]), float(p["lng"])
            alt = float(p.get("alt") or 0.0)
            acc = float(p.get("acc") or 0.0)
            if not (-90 <= lat <= 90 and -180 <= lng <= 180):
                raise ValueError("out of range")
        except (ValueError, KeyError, json.JSONDecodeError) as e:
            return self._send(400, json.dumps({"error": str(e)}))

        if acc > MAX_ACCURACY_M:
            state["skipped"] += 1
            return self._send(200, json.dumps({"ok": False, "reason": "accuracy", **state}))

        # รูปแบบของ emulator: geo fix <ลองจิจูด> <ละติจูด> [ความสูง] [จำนวนดาวเทียม]
        r = subprocess.run([ADB, "emu", "geo", "fix", f"{lng:.7f}", f"{lat:.7f}", f"{alt:.1f}", "10"],
                           capture_output=True, text=True, timeout=5)
        if r.returncode != 0 or "KO" in r.stdout:
            print("adb error:", r.stdout.strip(), r.stderr.strip(), file=sys.stderr)
            return self._send(502, json.dumps({"error": "adb failed — emulator เปิดอยู่ไหม?"}))
        state["count"] += 1
        state["last"] = {"lat": lat, "lng": lng, "acc": acc}
        state["last_at"] = time.time()
        print(f"#{state['count']:4d}  {lat:.6f}, {lng:.6f}  ±{acc:.0f} ม.", flush=True)
        return self._send(200, json.dumps({"ok": True, **state}))


if __name__ == "__main__":
    print(f"GPS bridge พร้อมที่ http://localhost:{PORT}/?k={TOKEN}")
    print(f"บน iPhone ให้เปิด: <ลิงก์ https จาก cloudflared>/?k={TOKEN}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
