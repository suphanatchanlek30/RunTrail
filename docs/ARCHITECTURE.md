# สถาปัตยกรรมและรายละเอียดทางเทคนิค

[← กลับไปหน้า README](../README.md)

ภาพรวมระบบ เทคโนโลยี โครงสร้างโฟลเดอร์ API และสูตรคำนวณ

- [สถาปัตยกรรม](#สถาปัตยกรรม)
- [เทคโนโลยีที่ใช้](#เทคโนโลยีที่ใช้)
- [โครงสร้างโปรเจค](#โครงสร้างโปรเจค)
- [API โดยสรุป](#api-โดยสรุป)
- [สูตรคำนวณและการตรวจการวิ่ง](#สูตรคำนวณและการตรวจการวิ่ง)

---

## สถาปัตยกรรม

```
┌──────────────────── แอป Android (Kotlin + XML) ────────────────────┐
│  Activity (ViewBinding)                                            │
│     │ อ่าน/เขียน                                                     │
│     ▼                                                              │
│  RunRepository ──► SQLite  runs · run_points · splits              │ ◄─ แหล่งข้อมูลหลัก ใช้ได้ออฟไลน์
│  RunTrackingService (foreground, location) ── GPS ทำงานตอนปิดจอ    │
│  SyncEngine + WorkManager ── ส่ง/ดึงข้อมูลเมื่อมีเน็ต                  │
│     │ OkHttp + JWT (refresh token อัตโนมัติ, gzip)                  │
│  หน้าจอ (อันดับ · แจ้งเตือน · แชร์ · รหัสผ่าน) ── Volley              │
│  ReminderScheduler ── AlarmManager → ReminderReceiver → แจ้งเตือน  │
└─────┼──────────────────────────────────────────────────────────────┘
      │ HTTPS /api/v1
      ▼
┌──────────────────── Backend (Go + Fiber, Docker) ──────────────────┐
│  auth · users · runs · photos · share · leaderboard · notifications│
│     │                          │                                   │
│     ▼                          ▼                                   │
│  PostgreSQL 17           S3 / RustFS (presigned URL)               │
└────────────────────────────────────────────────────────────────────┘
      หน้าเว็บแชร์  http://<host>:8080/s/{code}
```

- รูปภาพอัปโหลดจากมือถือ **ตรงไป S3** ด้วย presigned URL (ไม่ผ่าน Go)
- เซิร์ฟเวอร์ **คำนวณระยะใหม่จากจุด GPS** ด้วยสูตรเดียวกับแอป และตรวจความน่าเชื่อก่อนนับเข้าอันดับ

---

## เทคโนโลยีที่ใช้

**แอป Android**

| ส่วน | ที่เลือกใช้ |
|---|---|
| ภาษา / UI | Kotlin 100% · XML Layout + ViewBinding · Material Components (Material3) |
| Build | Gradle Kotlin DSL · Version Catalog · AGP 9.3.3 · `compileSdk 37` · `minSdk 28` · `targetSdk 37` |
| ฐานข้อมูล | SQLite (`SQLiteOpenHelper`) |
| เครือข่าย | Volley (คำขอจากหน้าจอ) · OkHttp 5 (ซิงก์เบื้องหลัง) · `org.json` · token เข้ารหัส AES-GCM ด้วย Android Keystore |
| งานเบื้องหลัง | WorkManager (ซิงก์) · Foreground Service (บันทึกการวิ่ง) |
| แผนที่ / ตำแหน่ง | osmdroid 6.1 (OpenStreetMap) · `LocationManager` (GPS) |
| เซนเซอร์ | Step Counter · Rotation Vector · Magnetometer · Accelerometer · Barometer · Light · Temperature |
| อื่น ๆ | ZXing (QR) · ExifInterface · กราฟและภาพแชร์วาดเองด้วย `Canvas` |
| ฟอนต์ / ไอคอน | Anuphan · IBM Plex Sans Thai · Lucide |

**Backend**

| ส่วน | ที่เลือกใช้ |
|---|---|
| ภาษา / Framework | Go 1.27 · Fiber v3 |
| ฐานข้อมูล | PostgreSQL 17 · pgx v5 · goose (migration ฝังใน binary) |
| เก็บไฟล์ | S3 API (aws-sdk-go-v2) · RustFS ตอนรันในเครื่อง / AWS S3 ตอนขึ้นจริง |
| ความปลอดภัย | argon2id · JWT (HS256) · refresh token แบบหมุนเวียน · rate limit |
| รันระบบ | Docker multi-stage (distroless) · Docker Compose · Makefile |
| ทดสอบ | `go test` + testcontainers (Postgres จริง) · `scripts/smoke.sh` |

---

## โครงสร้างโปรเจค

```
RunTrail/
├── app/                                  แอป Android
│   └── src/
│       ├── main/java/com/example/runtrail/
│       │   ├── App.kt                    เริ่มแอป: เปิด DB · สร้างตัวเชื่อม backend · ตั้งเวลาซิงก์
│       │   ├── data/                     Run · RunDb (SQLite) · RunRepository · PrefsHelper · Accounts · RunBackup · BackupInfo
│       │   ├── domain/                   Kotlin ล้วน (ทดสอบบน JVM ได้)
│       │   │                             TrackMath · RunCalculator · LiveRunState · Codec · Fmt ·
│       │   │                             PaceColor · Periods · RouteGenerator · ShareImageLayout
│       │   ├── net/                      ApiConfig · VolleyApi (Volley) · ApiClient (OkHttp + refresh) · RunTrailApi · RunJson · TokenStore
│       │   ├── reminder/                 ReminderScheduler (AlarmManager) · ReminderReceiver · BootReceiver · ReminderNotifier
│       │   ├── sync/                     SyncEngine · SyncManager (WorkManager)
│       │   ├── service/                  RunTrackingService (foreground, type=location)
│       │   ├── sensor/                   LocationTracker · CadenceTracker · CompassHelper · SensorHelper
│       │   └── ui/
│       │       ├── onboarding/           Welcome · Permissions · ProfileSetup
│       │       ├── auth/                 Login · Register
│       │       ├── home/                 หน้าหลัก
│       │       ├── live/                 วิ่งสด
│       │       ├── detail/               สรุปผล · กราฟ/Splits
│       │       ├── replay/  edit/        Replay · เพิ่ม/แก้ไขการวิ่ง
│       │       ├── history/  records/    ประวัติ · สถิติส่วนตัว
│       │       ├── leaderboard/          อันดับโลก
│       │       ├── notifications/        ศูนย์แจ้งเตือน · Notifier
│       │       ├── share/                ShareSheet · ภาพแชร์ · หน้าเว็บแชร์
│       │       ├── settings/  sensor/    ตั้งค่า · ตรวจเซนเซอร์
│       │       ├── common/               BottomNav · Dialogs · Sheets · Async · ImageStore ฯลฯ
│       │       └── widget/               MapRouteView · OsmRoute · กราฟ · HoldToStop · ToggleSwitch
│       ├── main/res/                     layout · drawable · font · values (ภาษาไทยทั้งหมด) · xml
│       ├── debug/                        อนุญาต HTTP + สิทธิ์เครือข่ายภายใน (เฉพาะ build debug)
│       ├── test/                         JVM unit test
│       └── androidTest/                  instrumented test (SQLite · ปิดจอ · ซิงก์กับ backend จริง)
├── backend/                              เซิร์ฟเวอร์ Go
│   ├── cmd/api/                          main: config → DB → migrate → listen
│   ├── internal/
│   │   ├── api/                          handler ทุก endpoint + หน้าเว็บแชร์ (templates/)
│   │   ├── track/                        คำนวณระยะ/splits/best effort · ตรวจโกง · ตัด 200 ม.
│   │   ├── auth/                         argon2id · JWT · refresh token · share code
│   │   ├── db/                           pgx pool + migrations
│   │   ├── storage/                      S3 client (+ ตัวจำลองสำหรับ test)
│   │   ├── httpx/                        รูปแบบ error · อ่าน JSON/gzip · validation
│   │   └── config/                       env vars
│   ├── scripts/smoke.sh                  ทดสอบทั้ง flow กับเซิร์ฟเวอร์จริง
│   ├── docker-compose.yml  Dockerfile  Makefile  .env.example
│   └── README.md                         วิธีรัน + เอกสาร API ฉบับเต็ม
├── tools/gps-bridge/                     ส่ง GPS จริงจาก iPhone เข้า Emulator
├── docs/
│   ├── FEATURES.md · ARCHITECTURE.md · SETUP.md · SECURITY.md · COURSE.md · DESIGN.md
│   │                                     รายละเอียดที่ลิงก์จาก README
│   ├── BACKEND_PLAN.md                   แผนและการตัดสินใจออกแบบระบบ
│   ├── prompts/                          prompt ที่ใช้สั่งงาน AI
│   ├── screenshots/                      ภาพหน้าจอใน README
│   └── logo.png
└── gradle/  build.gradle.kts  settings.gradle.kts
```

---

## API โดยสรุป

ทุก endpoint อยู่ใต้ `/api/v1` (🔒 = ต้องส่ง `Authorization: Bearer <token>`) — ฉบับเต็มพร้อมตัวอย่างอยู่ที่ [`backend/README.md`](../backend/README.md)

| กลุ่ม | Endpoint |
|---|---|
| บัญชี | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/change-password` 🔒 |
| ผู้ใช้ 🔒 | `GET/PATCH/DELETE /me` · `GET /me/stats` · `/me/avatar/*` · `GET /users/{id}/summary` |
| การวิ่ง 🔒 | `PUT /runs/{uuid}` (upsert) · `PATCH` · `DELETE` · `GET /runs?updated_since=&cursor=` · `GET /runs/{uuid}` · `/track` |
| รูป 🔒 | `POST /runs/{uuid}/photo/upload-url` → PUT ไป S3 → `POST …/photo/confirm` · `DELETE …/photo` |
| แชร์ | `POST/DELETE /runs/{uuid}/share` 🔒 · `GET /public/runs/{code}` · หน้าเว็บ `GET /s/{code}` |
| อันดับ 🔒 | `GET /leaderboard?metric=&period=&scope=` · `GET /leaderboard/me` |
| แจ้งเตือน 🔒 | `GET /notifications` · `POST /notifications/read` |
| ระบบ | `GET /healthz` · `GET /readyz` |

---

## สูตรคำนวณและการตรวจการวิ่ง

| ค่า | สูตร |
|---|---|
| ระยะทางระหว่าง 2 จุด | Haversine: `d = 2R·asin(√(sin²(Δφ/2) + cosφ₁·cosφ₂·sin²(Δλ/2)))`, R = 6,371 กม. |
| เพซ (วินาที/กม.) | `เวลาเคลื่อนที่ ÷ (ระยะทาง ÷ 1000)` |
| เพซปัจจุบัน | ระยะและเวลาของจุด GPS ใน 30 วินาทีล่าสุด |
| แคลอรี่ | `MET × น้ำหนัก(กก.) × ชั่วโมง` โดย `MET = (0.2 × ความเร็ว(ม./นาที) + 3.5) ÷ 3.5` (ACSM) |
| Split | เวลาที่ผ่านแต่ละหลักกิโลเมตร ประมาณเชิงเส้นระหว่างจุด GPS |
| ความสูงสะสม | รวมเฉพาะช่วงขึ้น ตัดการแกว่งต่ำกว่า 2 ม. |
| Cadence | ก้าวที่เพิ่มขึ้นใน 30 วินาทีล่าสุด ÷ เวลาเป็นนาที |
| สีตามเพซ | เร็ว < 5:50 · คงที่ ≤ 6:15 · ปานกลาง ≤ 6:40 · ช้ากว่านั้น |
| Best 1/5/10 กม. (server) | ช่วงที่เร็วที่สุดจริงในเส้นทาง (sliding window) |

**เซิร์ฟเวอร์นับการวิ่งเข้าอันดับเมื่อผ่านทุกข้อ** (ไม่ผ่านก็ยังเก็บไว้ แต่ไม่นับ และบอกเหตุผลในแอป):
อย่างน้อย 30 จุด · จุดห่างกันไม่เกิน 10 วินาที · ระยะต่างจากแอปไม่เกิน 5% · เพซเฉลี่ยไม่เร็วกว่า 2:50/กม. ·
ไม่มีช่วง 30 วินาทีที่เร็วกว่า 7 ม./วิ · เวลาไม่อยู่ในอนาคต · ระยะช่วงที่กดหยุดไม่นับ · การวิ่งที่บันทึกเองไม่นับ
