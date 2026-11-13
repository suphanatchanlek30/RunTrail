# ติดตั้ง รัน และทดสอบ

[← กลับไปหน้า README](../README.md)

วิธีรัน backend และแอป ทดสอบด้วย GPS และรัน test อัตโนมัติ

- [เริ่มต้นใช้งาน](#เริ่มต้นใช้งาน)
- [ทดสอบด้วย GPS](#ทดสอบด้วย-gps)
- [การทดสอบอัตโนมัติ (Test)](#การทดสอบอัตโนมัติ-test)

---

## เริ่มต้นใช้งาน

### สิ่งที่ต้องมี
- Android Studio (รองรับ AGP 9.3.3) + Android SDK 37 · อุปกรณ์หรือ Emulator Android 9 ขึ้นไป
- Docker Desktop (สำหรับ backend) · `make` · `jq` (ใช้กับ smoke test)

### 1) ดาวน์โหลดโปรเจค
```bash
git clone https://github.com/suphanatchanlek30/RunTrail.git
cd RunTrail
```

### 2) รัน backend
```bash
cd backend
make env      # สร้าง .env: ใส่ IP ของเครื่องและสุ่มรหัสลับให้ (ครั้งแรกเท่านั้น)
make up       # build + รัน PostgreSQL, RustFS (S3) และ API
curl localhost:8080/readyz     # {"db":"ok","storage":"ok"}
```
รายละเอียดทั้งหมดอยู่ที่ [`backend/README.md`](../backend/README.md)

### 3) รันแอป
เปิดโฟลเดอร์ `RunTrail` ใน Android Studio → รอ Gradle sync → เลือกอุปกรณ์ → **Run ▶**
หรือผ่าน command line:
```bash
./gradlew assembleDebug        # ไฟล์ APK: app/build/outputs/apk/debug/app-debug.apk
```

### 4) เชื่อมแอปกับ backend
ในหน้าเข้าสู่ระบบ แตะ **"เซิร์ฟเวอร์"** แล้วใส่ที่อยู่:

| อุปกรณ์ | ที่อยู่ |
|---|---|
| Emulator | `http://10.0.2.2:8080` (ค่าเริ่มต้น) |
| มือถือจริง (Wi-Fi เดียวกับเครื่องที่รัน backend) | `http://<IP ของเครื่อง>:8080` — ดู IP ด้วย `ipconfig getifaddr en0` |

- Android 17 จะถามสิทธิ์ **"อุปกรณ์ใกล้เคียง/เครือข่ายภายใน"** ต้องกด **อนุญาต** ไม่อย่างนั้นต่อเซิร์ฟเวอร์ในวง Wi-Fi ไม่ได้
- ถ้า IP ของเครื่องเปลี่ยน (ย้าย Wi-Fi) ให้รัน `make ip` แล้ว `make up` — อัปเดต `HOST_IP`, `PUBLIC_BASE_URL`, `S3_PUBLIC_ENDPOINT` ใน `backend/.env` ให้ โดยไม่แตะรหัสผ่าน (ลิงก์แชร์และรูปอ้างอิง IP นี้)
- ไม่ล็อกอินก็ใช้งานได้ (Guest) — การวิ่งจะรออยู่ในเครื่องและอัปโหลดเมื่อเข้าสู่ระบบ

---

## ทดสอบด้วย GPS

**Emulator — Extended Controls**: `⋯` → **Location** → แท็บ **Routes** → วาดเส้นทางหรือโหลด GPX → **Play route**

**Emulator — คำสั่ง adb** (`ลองจิจูด ละติจูด ความสูง` ทีละจุด ทุก 1 วินาที)
```bash
adb emu geo fix 100.5385 13.7290 5
adb emu geo fix 100.5386 13.7293 5
```

**ใช้ GPS จริงจาก iPhone** (ไม่มีมือถือ Android): ถือโน้ตบุ๊กกับ iPhone ออกไปวิ่งได้จริง
ดูวิธีที่ [`tools/gps-bridge/README.md`](../tools/gps-bridge/README.md)
```
iPhone (Safari) → Cloudflare Tunnel (HTTPS) → bridge.py → adb emu geo fix → Emulator
```

> 💡 เครื่องจริงให้ผลดีที่สุด — Emulator ไม่มี Step Counter และเซนเซอร์บางตัว (ช่อง Cadence จะเป็น `--`)

---

## การทดสอบอัตโนมัติ (Test)

| ชุด | คำสั่ง | ครอบคลุม |
|---|---|---|
| แอป · JVM (77 ข้อ) | `./gradlew testDebugUnitTest` | เวลาเตือนไปวิ่งรอบถัดไป (ข้ามวันที่ไม่เลือก) · ไฟล์สำรอง JSON ส่งออก/นำเข้า · สูตร TrackMath/RunCalculator · สถานะการวิ่งสด (auto-pause, ช่วงหยุด, จุด GPS มาเป็นชุด, จุดแรกค้าง) · JSON · HTTP client (refresh token พร้อมกันแล้ว refresh ครั้งเดียว, gzip) · ตำแหน่งในภาพแชร์ · หน่วย กม./ไมล์ · cadence/แคลอรี่/เข็มทิศ · เลือกแหล่ง GPS · ย่อรูป |
| แอป · บนเครื่อง (41 ข้อ) | `./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080` | SQLite · **อัปเกรดฐานข้อมูล v1 → v2 ข้อมูลไม่หาย** · **Volley กับ backend จริง** (GET/PATCH/POST · DELETE ตอบ 204 · token หมดอายุแล้ว refresh + ส่งซ้ำ · error 404) · **บันทึกต่อเนื่องตอนปิดจอ** · วาดภาพแชร์ · **ซิงก์กับ backend จริงทั้งวงจร** (ออฟไลน์ → ซิงก์ครั้งเดียว → รูปขึ้น S3 → ลิงก์แชร์ → แก้/ลบ → เครื่องใหม่ดึงคืน) · **ตรวจทุกปุ่มทุกหน้า** (ไม่มีปุ่มตาย · กดแล้วไม่เด้ง) · วัดความเร็วกับข้อมูล 1,000 รอบ · **บันทึกการวิ่งผ่านหน้าจอด้วย GPS ของ emulator** · เซนเซอร์ที่ตั้งค่าจาก Mac · ปุ่มเฉพาะบัญชีกับ backend จริง · หน้าจอตอนออฟไลน์ · การวิ่ง 60,000 จุด |
| Backend · Go | `cd backend && make test` | unit + integration กับ PostgreSQL จริง: auth, last-write-wins, UUID ของคนอื่น, gzip bomb, sync cursor, รูป, อันดับ, แชร์ซ่อนบ้าน, การแจ้งเตือน, ลบบัญชี |
| Backend · smoke (29 ข้อ) | `cd backend && make smoke` | ทั้ง flow ผ่าน HTTP กับ Docker จริง |

> ⚠️ `connectedDebugAndroidTest` จะ **ถอนแอปออกจากเครื่องหลังรันเสร็จ** (ข้อมูลในแอปหาย) ถ้ากำลังใช้แอปอยู่ ให้ใช้ `installDebug installDebugAndroidTest` แล้วรัน `adb shell am instrument` แทน
