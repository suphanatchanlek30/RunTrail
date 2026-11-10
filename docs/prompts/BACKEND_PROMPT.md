# Prompt สำหรับให้ AI สร้าง Backend RunTrail

> วิธีใช้: คัดลอกส่วน **Prompt หลัก** ทั้งก้อนไปวางให้ AI (Claude Code ที่เปิดในโฟลเดอร์โปรเจค RunTrail)
> ถ้างานยาวจนต้องเริ่มแชตใหม่ ให้ใช้ **Prompt ทำต่อ** ท้ายไฟล์

---

## Prompt หลัก

```text
คุณคือ Senior Backend Engineer (Go) งานนี้คือสร้าง backend ให้แอป Android "RunTrail"
ให้ใช้งานได้จริง รันบน Docker ได้ และมี test ครบ ทำงานในโฟลเดอร์โปรเจคนี้

## ขั้นที่ 1 — อ่านและทำความเข้าใจก่อน (ยังไม่ต้องเขียนโค้ด)
1. อ่าน docs/BACKEND_PLAN.md ทั้งไฟล์ ไฟล์นี้คือ "สัญญา (contract)" ของระบบ:
   schema, API, กฎ offline-first, การตรวจโกง, ความเป็นส่วนตัว, Docker, เฟสงาน
2. อ่านโค้ด frontend เพื่อให้ API ตรงกับแอปจริง อย่างน้อยไฟล์เหล่านี้:
   - app/src/main/java/com/example/runtrail/data/Run.kt          (โมเดล Run, TrackPoint, Split, SyncStatus)
   - app/src/main/java/com/example/runtrail/data/RunRepository.kt (การค้นหา/กรอง/เรียง/สถิติ)
   - app/src/main/java/com/example/runtrail/data/PrefsHelper.kt   (ข้อมูลโปรไฟล์และค่าตั้งค่า)
   - app/src/main/java/com/example/runtrail/domain/TrackMath.kt   (Haversine, splits, elevation, GpsFilter)
   - app/src/main/java/com/example/runtrail/domain/RunCalculator.kt (เพซ, แคลอรี่, PR)
   - app/src/main/java/com/example/runtrail/ui/live/LiveRunActivity.kt
   - app/src/main/java/com/example/runtrail/ui/share/ShareSheet.kt และ WebPreviewActivity.kt
   - app/src/main/java/com/example/runtrail/ui/records/RecordsActivity.kt
3. สรุปให้ผมสั้น ๆ (ภาษาไทย) ก่อนลงมือ:
   - ตารางเทียบฟิลด์ Kotlin ↔ JSON (snake_case) ↔ คอลัมน์ Postgres
   - สูตรใน TrackMath / RunCalculator ที่ server ต้องคำนวณ "ให้ได้ผลเท่ากับแอป"
   - จุดที่แผนกับโค้ดไม่ตรงกัน หรือสิ่งที่แผนยังไม่ได้ระบุ พร้อมสิ่งที่คุณจะเลือกทำ
   แล้วทำต่อได้เลย ไม่ต้องรอผมตอบ เว้นแต่จะเจอเรื่องที่ตัดสินใจแทนไม่ได้จริง ๆ

## ขั้นที่ 2 — สร้าง backend ในโฟลเดอร์ backend/ ตามแผนข้อ 2
เทคโนโลยี (ตามแผน): Go เวอร์ชันล่าสุดในเครื่อง · Fiber v3 (เช็คเวอร์ชันล่าสุดด้วย
`go list -m -versions github.com/gofiber/fiber/v3` อย่าเดา) · pgx/v5 · sqlc · goose (ฝัง migration ใน binary)
· argon2id · golang-jwt/v5 · aws-sdk-go-v2 (S3 + presign) · slog · validator/v10 · testcontainers-go

ทำทีละส่วนตามลำดับนี้ แต่ละส่วนต้อง build ผ่านและ test ผ่านก่อนไปส่วนถัดไป:
 A. โครงโปรเจค + config (env) + /healthz /readyz + error format เดียวกันทุก endpoint + graceful shutdown
 B. Migrations ตาม schema ในแผนข้อ 2.3 (users, refresh_tokens, runs, run_tracks, run_splits, photo_uploads)
 C. Auth: register / login / refresh (หมุน token + reuse detection + grace window 30 วิ) / logout / change-password
    + rate limit + middleware ตรวจ JWT
 D. /me: GET / PATCH / DELETE / avatar upload-url / stats
 E. Runs: PUT upsert ด้วย UUID จากแอป (idempotent, last-write-wins ด้วย client_updated_at,
    UUID ของคนอื่น → 409 ห้ามเขียนทับ) / PATCH / DELETE (soft, idempotent) / GET list (updated_since + cursor) /
    GET one / GET track · รองรับ gzip พร้อมจำกัดขนาดหลังคลาย
 F. Track service: กรองจุด, คำนวณระยะ/splits/best 1K-5K-10K ใหม่, ตรวจความน่าเชื่อ → verified + verify_reason
    (เกณฑ์ตามแผนข้อ 2.4)
 G. Photos: presigned PUT (S3 client ตัวที่ 2 ใช้ S3_PUBLIC_ENDPOINT + UsePathStyle) / confirm ด้วย HeadObject /
    delete / presigned GET ใน response / job ลบ upload ที่ไม่ confirm เกิน 24 ชม.
 H. Leaderboard: metric distance|best_5k|best_10k · period week|month|all (ISO week UTC) · scope global|country ·
    /leaderboard/me · นับเฉพาะ verified + source=gps + ไม่ถูกลบ + opt-in · cache 60 วิ
 I. Share: POST/DELETE /runs/{id}/share · GET /public/runs/{code} (ตัด 200 ม. แรก/สุดท้ายที่ server, ซ่อนรูป/แคลอรี่ตามตัวเลือก)
    · หน้าเว็บ /s/{code} ด้วย html/template + Leaflet + OG meta · share_code 8 ตัวจาก crypto/rand · URL จาก PUBLIC_BASE_URL
 J. Docker: Dockerfile (multi-stage, distroless, nonroot) + docker-compose.yml (db, minio, minio-init, api)
    + .env.example + Makefile (up, down, logs, test, sqlc, lint) + backend/README.md ภาษาไทย

## ขั้นที่ 3 — Test (บังคับ ไม่ใช่ทางเลือก)
- Unit test: TrackMath ฝั่ง Go (เทียบผลกับตัวอย่างที่คำนวณจากสูตรเดียวกับแอป), การตรวจโกง, การตัด 200 ม.,
  สร้าง share code, hash/verify password, JWT
- Integration test กับ Postgres จริง (testcontainers) ทุก endpoint อย่างน้อย:
  * สมัครซ้ำอีเมลเดิม → 409 · ล็อกอินผิด → 401
  * refresh แล้วใช้ token เก่าซ้ำหลัง 30 วิ → ถูกปฏิเสธและทั้ง family ถูกเพิกถอน · ภายใน 30 วิ → ได้คู่เดิม
  * PUT /runs/{uuid} ส่งซ้ำ 3 ครั้ง → มีแค่ 1 แถว
  * PUT ด้วย UUID ของผู้ใช้อื่น → 409 และข้อมูลเดิมไม่เปลี่ยน
  * client_updated_at เก่ากว่าของ server → ไม่เขียนทับ
  * GET /runs/{uuid} ของคนอื่น → 404
  * การวิ่ง manual และการวิ่งที่เร็วผิดปกติ → ไม่ขึ้น leaderboard
  * 3 ผู้ใช้ ระยะต่างกัน → อันดับเรียงถูก และ /leaderboard/me ถูก
  * /public/runs/{code} → ไม่มีจุดใดอยู่ในรัศมี 200 ม. จากจุดเริ่ม/จบ · ยกเลิกแชร์แล้ว → 404
  * body gzip ที่คลายแล้วเกินขนาด → 413
  * photo confirm โดยยังไม่ได้อัปโหลดจริง → 400
- Test ต้องรันได้ด้วยคำสั่งเดียว `make test` (หรือ `go test ./...`) และต้องผ่านทั้งหมด
- รัน `go vet ./...` (และ golangci-lint ถ้ามีในเครื่อง) ให้สะอาด

## ขั้นที่ 4 — รันจริงและพิสูจน์ว่าใช้ได้
1. `docker compose up -d --build` ในโฟลเดอร์ backend แล้วเช็ค `curl localhost:8080/readyz`
2. เขียนสคริปต์ backend/scripts/smoke.sh ที่ใช้ curl ไล่ทั้ง flow:
   register → login → PATCH /me → PUT run (ใช้ track จริงรอบสวนลุมพินี ~2 กม. ที่สร้างจากพิกัดจริง)
   → ขอ upload-url → PUT รูปไป MinIO → confirm → share → เปิด /s/{code} → leaderboard → refresh → logout
   แล้วรันให้ผ่าน แนบผลลัพธ์ให้ผมดู
3. ใส่ตัวอย่าง request/response ของทุก endpoint ไว้ใน backend/README.md (หรือไฟล์ openapi.yaml ถ้าทำได้)

## กฎที่ต้องทำตาม
- แผนใน docs/BACKEND_PLAN.md คือสัญญา ถ้าจำเป็นต้องเปลี่ยน API/schema ให้แก้ไฟล์แผนด้วยและบอกเหตุผล
- ห้ามแก้โค้ด Android ใน app/ ในงานนี้ (จะทำในงานถัดไป) — แค่อ่าน
- ห้าม commit ความลับ: .env, key, token ต้องอยู่ใน .gitignore · มีแค่ .env.example
- ห้ามรัน `terraform apply` หรือคำสั่งที่สร้างทรัพยากรบน AWS
- ทำบน branch ใหม่ชื่อ `backend` และ commit แยกตามส่วน A–J (ข้อความ commit ภาษาอังกฤษ สั้น ชัด)
- ห้ามบอกว่า "เสร็จ" ถ้า test ยังไม่ผ่านหรือยังไม่ได้รันจริง ถ้าข้ามอะไรหรือมีอะไรไม่ทำงาน ให้บอกตรง ๆ
- comment ในโค้ดสั้น อ่านง่าย อธิบาย "ทำไม" มากกว่า "ทำอะไร"

## รายงานตอนจบ (ภาษาไทย)
1. สรุปสิ่งที่ทำ + endpoint ทั้งหมด
2. ผล `make test` (จำนวน test ผ่าน/ไม่ผ่าน) และผล smoke.sh
3. วิธีรันสำหรับผม (Emulator ใช้ http://10.0.2.2:8080 · มือถือจริงใช้ IP ของ Mac)
4. สิ่งที่ยังไม่ได้ทำ / ข้อจำกัด / สิ่งที่แอป Android ต้องแก้เพื่อเชื่อมต่อ (เตรียมไว้สำหรับงานถัดไป)
```

---

## Prompt ทำต่อ (ใช้เมื่อเริ่มแชตใหม่กลางทาง)

```text
ทำงาน backend ของ RunTrail ต่อจากเดิม
1. อ่าน docs/BACKEND_PLAN.md และ docs/prompts/BACKEND_PROMPT.md (ส่วน Prompt หลัก) เพื่อรู้เป้าหมายและกฎ
2. ดู `git log --oneline backend` และโครง backend/ ว่าทำถึงส่วนไหนแล้ว (A–J)
3. รัน `make test` เพื่อดูสถานะตอนนี้ ถ้ามีอะไรพังให้แก้ก่อน
4. ทำส่วนถัดไปต่อจนครบ ตามกฎและรูปแบบรายงานเดิม
```

---

## Prompt งานถัดไป: เชื่อมแอป Android (ใช้หลัง backend เสร็จ)

```text
backend ใน backend/ เสร็จและผ่าน test แล้ว ตอนนี้ให้เชื่อมแอป Android (Kotlin + XML + ViewBinding เท่านั้น ห้ามใช้ Compose)
1. อ่าน docs/BACKEND_PLAN.md ข้อ 0, 3, 4 และ backend/README.md
2. ทำเฟส 1 ก่อน: SQLite แทน RunRepository แบบ in-memory (คงชื่อเมธอดเดิม) · RunTrackingService
   (foreground, type=location, จับเวลาด้วย elapsedRealtime) · แก้บั๊กในแผนข้อ 4 ทีละข้อ · ล็อกแนวตั้ง
3. แล้วทำเฟส 3–5: Login/Register/Guest · TokenStore + OkHttp Authenticator (lock ตอน refresh) ·
   SyncWorker (WorkManager, ซิงก์เฉพาะ owner ปัจจุบัน) · ShareSheet/WebPreview ใช้ API จริง · แท็บอันดับ
4. ต้องใช้ได้ออฟไลน์แบบ Strava: วิ่งตอนโหมดเครื่องบินได้ครบ เปิดเน็ตแล้วอัปโหลดเองแค่ 1 ครั้ง
5. ทุกครั้งที่ทำเสร็จหนึ่งส่วน ให้ `./gradlew assembleDebug` และ unit test ผ่าน
   และทดสอบตามเกณฑ์ผ่านของแต่ละเฟสในแผนข้อ 7 แล้วรายงานผลเป็นภาษาไทย
```
