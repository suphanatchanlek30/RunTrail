# ติดตั้ง รัน และทดสอบ

[← กลับไปหน้า README](../README.md)

**สารบัญ:** [เริ่มต้นใช้งาน](#เริ่มต้นใช้งาน) · [ทดสอบด้วย GPS](#ทดสอบด้วย-gps) · [การทดสอบอัตโนมัติ (Test)](#การทดสอบอัตโนมัติ-test)

---

## เริ่มต้นใช้งาน

### สิ่งที่ต้องมี

- Android Studio และ Android SDK 37
- Emulator หรือมือถือ Android 9 ขึ้นไป
- Docker Desktop และ `make` สำหรับ backend (`jq` ถ้าจะรัน smoke test)

### 1. ดาวน์โหลดโปรเจค

```bash
git clone https://github.com/suphanatchanlek30/RunTrail.git
cd RunTrail
```

### 2. รัน backend

```bash
cd backend
make env                    # สร้าง .env พร้อมรหัสลับแบบสุ่ม (ครั้งแรกครั้งเดียว)
make up                     # รัน PostgreSQL + RustFS (S3) + API
curl localhost:8080/readyz  # ควรได้ {"db":"ok","storage":"ok"}
```

คำสั่งอื่นและรายละเอียดทั้งหมดอยู่ที่ [`backend/README.md`](../backend/README.md)

### 3. รันแอป

เปิดโฟลเดอร์ `RunTrail` ใน Android Studio → รอ Gradle sync → เลือกอุปกรณ์ → กด **Run ▶**

หรือ build จาก command line:

```bash
./gradlew assembleDebug     # ได้ไฟล์ app/build/outputs/apk/debug/app-debug.apk
```

### 4. ต่อแอปกับ backend

ในหน้าเข้าสู่ระบบ แตะ **"เซิร์ฟเวอร์"** แล้วใส่ที่อยู่

| อุปกรณ์ | ที่อยู่ |
|---|---|
| Emulator | `http://10.0.2.2:8080` (ค่าเริ่มต้น) |
| มือถือจริง (Wi-Fi เดียวกัน) | `http://<IP ของเครื่อง>:8080` ดู IP ด้วย `ipconfig getifaddr en0` |

> [!TIP]
> - Android 17 จะถามสิทธิ์ "เครือข่ายภายใน" ต้องกด **อนุญาต** จึงจะต่อเซิร์ฟเวอร์ในวง Wi-Fi ได้
> - ย้าย Wi-Fi แล้ว IP เปลี่ยน ให้รัน `make ip` แล้ว `make up` ลิงก์แชร์และรูปจะใช้ IP ใหม่
> - ไม่ล็อกอินก็ใช้งานได้ การวิ่งจะรออยู่ในเครื่องแล้วอัปโหลดตอนเข้าสู่ระบบ

---

## ทดสอบด้วย GPS

| วิธี | ทำอย่างไร |
|---|---|
| Emulator: Extended Controls | `⋯` → **Location** → แท็บ **Routes** → วาดเส้นทางหรือโหลด GPX → **Play route** |
| Emulator: คำสั่ง adb | ส่งพิกัดทีละจุดทุก 1 วินาที (ดูตัวอย่างด้านล่าง) |
| GPS จริงจาก iPhone | ถือโน้ตบุ๊กกับ iPhone ออกไปวิ่งจริง ดู [`tools/gps-bridge/README.md`](../tools/gps-bridge/README.md) |

```bash
# ลำดับค่า: ลองจิจูด ละติจูด ความสูง
adb emu geo fix 100.5385 13.7290 5
adb emu geo fix 100.5386 13.7293 5
```

> [!NOTE]
> เครื่องจริงให้ผลดีที่สุด Emulator ไม่มี Step Counter ช่อง Cadence จึงแสดง `--`

---

## การทดสอบอัตโนมัติ (Test)

| ชุด | คำสั่ง |
|---|---|
| แอป · JVM (77 ข้อ) | `./gradlew testDebugUnitTest` |
| แอป · บนเครื่อง (41 ข้อ) | `./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080` |
| Backend | `cd backend && make test` |
| Backend · smoke (29 ข้อ) | `cd backend && make smoke` |

### แต่ละชุดตรวจอะไร

**แอป · JVM**
- สูตรคำนวณระยะ เพซ splits แคลอรี่ cadence และเข็มทิศ
- สถานะการวิ่งสด: auto-pause, ช่วงหยุด, จุด GPS ที่มาเป็นชุดตอนปิดจอ, จุดแรกที่ค้าง
- การซิงก์ทุกกรณี และ HTTP client (refresh token พร้อมกันแล้ว refresh ครั้งเดียว, gzip)
- เวลาเตือนไปวิ่งรอบถัดไป · ไฟล์สำรอง JSON · หน่วย กม./ไมล์ · ตำแหน่งในภาพแชร์

**แอป · บนเครื่อง**
- บันทึกการวิ่งต่อเนื่องตอนปิดจอ และบันทึกผ่านหน้าจอจริงด้วย GPS ของ Emulator
- ซิงก์กับ backend จริงทั้งวงจร: ออฟไลน์ → ซิงก์ → รูปขึ้น S3 → ลิงก์แชร์ → แก้/ลบ → เครื่องใหม่ดึงคืน
- Volley กับ backend จริง: GET, PATCH, POST, DELETE ที่ตอบ 204, token หมดอายุแล้ว refresh
- กดทุกปุ่มทุกหน้า ต้องไม่มีปุ่มตายและไม่เด้ง · หน้าจอตอนออฟไลน์
- อัปเกรดฐานข้อมูล v1 → v2 โดยข้อมูลไม่หาย · การวิ่ง 60,000 จุด · ความเร็วกับข้อมูล 1,000 รอบ

**Backend**
- Unit และ integration test กับ PostgreSQL จริง: auth, last-write-wins, UUID ของคนอื่น, gzip bomb,
  sync cursor, รูป, อันดับ, การซ่อนจุดรอบบ้าน, การแจ้งเตือน, ลบบัญชี
- Smoke test ทั้ง flow ผ่าน HTTP กับ Docker จริง

> [!WARNING]
> `connectedDebugAndroidTest` จะถอนแอปออกจากเครื่องหลังรันเสร็จ ข้อมูลในแอปจะหาย
> ถ้ากำลังใช้แอปอยู่ ให้รัน `./gradlew installDebug installDebugAndroidTest` แล้วใช้ `adb shell am instrument` แทน
