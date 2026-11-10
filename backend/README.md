# RunTrail Backend

Go 1.27 · Fiber v3.7 · PostgreSQL 17 · S3 (RustFS ตอนรันในเครื่อง / AWS S3 ตอนขึ้นจริง) · Docker

แผนและเหตุผลของการออกแบบทั้งหมดอยู่ที่ [`../docs/BACKEND_PLAN.md`](../docs/BACKEND_PLAN.md)

---

## รันในเครื่อง (Docker)

ต้องมี Docker Desktop เปิดอยู่

```bash
cd backend
make env        # สร้าง .env: ใส่ IP ของ Mac และสุ่มรหัสลับให้ (ถ้ามี .env อยู่แล้วจะไม่ทับ)
make up         # build + รัน db, s3, api
curl localhost:8080/readyz     # {"db":"ok","storage":"ok"}
make smoke      # ทดสอบทั้ง flow กับ server จริง (26 ข้อ)
```

| คำสั่ง | ทำอะไร |
|---|---|
| `make logs` | ดู log ของ api |
| `make down` | หยุด (ข้อมูลยังอยู่) |
| `make clean` | หยุดและ**ลบข้อมูลทั้งหมด** |
| `make test` | unit + integration test (เปิด Postgres ชั่วคราวเองผ่าน testcontainers) |
| `make test-race` | test พร้อมตรวจ data race |
| `make lint` | gofmt + go vet |
| `make run` | รัน Go นอก Docker (ต้อง `make up` ให้ db/s3 ทำงานก่อน และหยุด container api) |

### ที่อยู่ที่แอปต้องใช้
| อุปกรณ์ | Base URL |
|---|---|
| Android Emulator | `http://10.0.2.2:8080/api/v1/` หรือ `http://<HOST_IP>:8080/api/v1/` |
| มือถือจริง (Wi-Fi เดียวกับ Mac) | `http://<HOST_IP>:8080/api/v1/` |

`HOST_IP` อยู่ใน `.env` (ดูด้วย `ipconfig getifaddr en0`) — **ถ้า IP ของ Mac เปลี่ยน** ให้แก้ `HOST_IP`, `PUBLIC_BASE_URL`, `S3_PUBLIC_ENDPOINT` ใน `.env` แล้ว `make up` ใหม่
เพราะลิงก์อัปโหลดรูปถูกเซ็นกับ host นี้ ถ้าไม่ตรง มือถือจะอัปโหลดไม่ได้

> แอป debug ต้องอนุญาต HTTP (cleartext) ด้วย `src/debug/res/xml/network_security_config.xml` — ดูแผนข้อ 4 บั๊ก #11

### ทำไมใช้ RustFS แทน MinIO
MinIO หยุดแจก Docker image ฟรีแล้ว (`minio/minio` ดึงไม่ได้) จึงใช้ [RustFS](https://github.com/rustfs/rustfs) (Apache-2.0) ที่ใช้ S3 API เหมือนกัน
โค้ดใช้ AWS SDK ปกติ จึงสลับไป AWS S3 จริงได้โดยแค่เปลี่ยน env (ไม่ต้องแก้โค้ด) — console ดูไฟล์ได้ที่ `http://localhost:9001`

---

## โครงสร้าง

```
cmd/api/main.go            เริ่ม server: อ่าน env → ต่อ DB (รอจนพร้อม) → migrate → สร้าง bucket → listen · `api healthcheck`
internal/config            env vars ทั้งหมด
internal/db                pgx pool + goose migrations (ฝังใน binary, migrate ทุกครั้งที่เริ่ม)
internal/httpx             รูปแบบ error เดียวกันทุก endpoint · อ่าน JSON (รองรับ gzip + จำกัดขนาดหลังคลาย) · validation
internal/auth              argon2id · JWT · refresh token · share code
internal/track             สูตรจาก TrackMath.kt ของแอป (Haversine, splits, elevation, pace) + best effort + ตรวจโกง + ตัด 200 ม.
internal/storage           S3 client (2 ตัว: คุยเอง / เซ็น URL ให้มือถือ) + ตัวจำลองในหน่วยความจำสำหรับ test
internal/api               handler ทุก endpoint + หน้าเว็บแชร์ (templates/)
scripts/smoke.sh           ทดสอบ end-to-end ด้วย curl
```

---

## รูปแบบทั่วไป

- ทุก endpoint อยู่ใต้ `/api/v1` · JSON · เวลาเป็น RFC 3339 (UTC)
- ต้อง login: ส่ง header `Authorization: Bearer <access_token>`
- ส่ง body แบบ gzip ได้ (`Content-Encoding: gzip`) — แนะนำสำหรับ `PUT /runs` (ข้อมูลเล็กลง ~10 เท่า) · สูงสุด 5 MB หลังคลาย, 6 MB ก่อนคลาย
- Error ทุกตัวหน้าตาเดียวกัน:

```json
{ "error": { "code": "validation_failed", "message": "ข้อมูลไม่ถูกต้อง", "fields": { "email": "email" } } }
```

| HTTP | code ที่พบบ่อย | แอปควรทำ |
|---|---|---|
| 400 | `validation_failed`, `bad_json`, `upload_missing` | แสดง `message` / `fields` |
| 401 | `missing_token`, `invalid_token` | เรียก `/auth/refresh` แล้วลองใหม่ |
| 401 | `invalid_refresh_token`, `refresh_token_revoked`, `refresh_token_expired`, `refresh_token_reused` | ให้ login ใหม่ (**ข้อมูลในเครื่องไม่ต้องลบ**) |
| 404 | `not_found` | ไม่มี / ไม่ใช่ของเรา |
| 409 | `email_taken`, `run_id_taken`, `concurrent_upload` | `run_id_taken` = สร้าง UUID ใหม่แล้วส่งใหม่ · `concurrent_upload` = ลองใหม่ |
| 410 | `run_deleted` | การวิ่งนี้ถูกลบจากเครื่องอื่น → ลบในเครื่องด้วย |
| 413 | `body_too_large` | |
| 429 | `rate_limited` | รอแล้วลองใหม่ |

---

## Endpoints

### Health
| | |
|---|---|
| `GET /healthz` | process ทำงาน → `{"status":"ok"}` |
| `GET /readyz` | ต่อ DB และ S3 ได้ → `{"db":"ok","storage":"ok"}` (ไม่ได้ = 503) |

### Auth (จำกัด 10 ครั้ง/นาที/IP)

**`POST /auth/register`** → 201
```json
// request
{ "email": "ploy@example.com", "password": "password123", "display_name": "Ploy", "country_code": "TH", "device_name": "Pixel 9" }
// response (login ก็ได้รูปแบบเดียวกัน)
{
  "user": { "id": "7b1c…", "email": "ploy@example.com", "display_name": "Ploy", "country_code": "TH", "avatar_url": null,
            "first_name": null, "last_name": null, "weight_kg": null, "height_cm": null, "birth_year": null,
            "use_miles": false, "weekly_goal_km": 20, "target_pace_s": 360, "leaderboard_opt_in": true,
            "created_at": "2026-11-10T05:20:40Z", "updated_at": "2026-11-10T05:20:40Z" },
  "access_token": "eyJhbGciOi…", "refresh_token": "q8Zx…", "token_type": "Bearer", "expires_in": 900
}
```

**`POST /auth/login`** `{ "email", "password", "device_name"? }` → 200 · ผิด → 401 `invalid_credentials` (อีเมลไม่มีก็ตอบแบบเดียวกัน)

**`POST /auth/refresh`** `{ "refresh_token" }` → 200 คู่ token ใหม่ (**refresh token เปลี่ยนทุกครั้ง ต้องเก็บตัวใหม่**)
- ใช้ token เก่าซ้ำภายใน 30 วิ (UI กับ SyncWorker refresh พร้อมกัน) → ยังได้คู่ใหม่
- ใช้ token เก่าซ้ำหลัง 30 วิ → 401 `refresh_token_reused` และ token ทั้งชุดของเครื่องนั้นถูกยกเลิก
- ฝั่งแอปควรมี lock ให้ refresh ทีละครั้งอยู่ดี

**`POST /auth/logout`** `{ "refresh_token" }` → 204 (ยกเลิกเฉพาะเครื่องนี้)

**`POST /auth/change-password`** 🔒 `{ "old_password", "new_password" }` → 200 คู่ token ใหม่ · เครื่องอื่นทุกเครื่องถูก logout

### ผู้ใช้ 🔒
| | |
|---|---|
| `GET /me` | โปรไฟล์ (รูปแบบเดียวกับ `user` ด้านบน) |
| `PATCH /me` | ส่งเฉพาะช่องที่จะแก้ · ส่ง `null` = ล้างค่า |
| `GET /me/stats?tz=Asia/Bangkok` | สรุปเหมือนหน้า "สถิติส่วนตัว" |
| `POST /me/avatar/upload-url` → `POST /me/avatar/confirm` | รูปโปรไฟล์ (ขั้นตอนเหมือนรูปการวิ่ง) |
| `DELETE /me/avatar` | ลบรูปโปรไฟล์ |
| `DELETE /me` | ลบบัญชี: ลบการวิ่ง รูป ลิงก์แชร์ และ logout ทุกเครื่อง · อีเมลนำไปสมัครใหม่ได้ |

```json
// PATCH /me
{ "first_name": "พลอย", "weight_kg": 55, "height_cm": 162, "birth_year": 2004, "use_miles": false,
  "weekly_goal_km": 25, "target_pace_s": 345, "country_code": "TH", "leaderboard_opt_in": true }

// GET /me/stats?tz=Asia/Bangkok
{ "run_count": 12, "total_distance_m": 61234.5, "total_time_s": 22710, "total_calories": 4120,
  "best_1k_s": 301, "best_5k_s": 1702, "best_10k_s": null, "longest_m": 10120.4,
  "monthly": [ { "month": "2026-06", "km": 0 }, …, { "month": "2026-11", "km": 18.4 } ] }
```

### การวิ่ง 🔒 (หัวใจของการซิงก์ออฟไลน์)

**`PUT /runs/{uuid}`** — สร้างหรืออัปเดต · `uuid` สร้างในแอปตอนเริ่มวิ่ง
```json
{
  "title": "วิ่งเย็นริมสวน", "note": null, "source": "gps",
  "started_at": "2026-11-10T11:02:10Z", "ended_at": "2026-11-10T11:34:00Z", "timezone": "Asia/Bangkok",
  "distance_m": 5020.4, "moving_time_s": 1868, "calories": 342, "elevation_gain_m": 12,
  "cadence_spm": 168, "temp_c": null,
  "points": [[13.7290, 100.5385, 1762772530000, 5.0, 6.0], …],
  "pauses": [[1762772900000, 1762773020000]],
  "client_updated_at": "2026-11-10T11:34:05.123Z"
}
```
- `points` = `[lat, lng, เวลา epoch ms, ความสูง ม., ความแม่นยำ ม.]` ตรงกับ `TrackPoint` ในแอป · สูงสุด 50,000 จุด
- `pauses` = ช่วงที่หยุดวิ่ง `[เริ่ม ms, จบ ms]` (กดหยุดเองหรือ auto-pause) — **แอปต้องส่ง** เพราะแอปไม่นับระยะช่วงหยุด
  ถ้าไม่ส่ง server จะนับระยะที่เดินระหว่างหยุดด้วย แล้วการวิ่งอาจไม่ผ่านการตรวจ (`distance_mismatch`)
- `source: "manual"` (เพิ่มเอง) ต้องไม่มี `points` และไม่เข้าอันดับ
- ส่งครั้งแรก → **201** · ส่งซ้ำ → **200** (ไม่มีแถวซ้ำ)
- `client_updated_at` เก่ากว่าหรือเท่ากับของ server → server ไม่เขียนทับ ตอบ 200 พร้อมข้อมูลของ server
- UUID เป็นของคนอื่น → 409 `run_id_taken` · ถูกลบไปแล้ว → 410 `run_deleted`

Response (ใช้กับ GET/PATCH ด้วย):
```json
{
  "id": "4f0c…", "title": "วิ่งเย็นริมสวน", "note": null, "source": "gps",
  "started_at": "…", "ended_at": "…", "timezone": "Asia/Bangkok",
  "distance_m": 5020.4,          // ที่แอปวัดได้ (ใช้แสดงผล)
  "server_distance_m": 5011.8,   // server คำนวณใหม่จากจุด (ใช้จัดอันดับ)
  "moving_time_s": 1868, "calories": 342, "elevation_gain_m": 12, "cadence_spm": 168, "temp_c": null,
  "verified": true, "verify_reason": null,
  "best_1k_s": 352, "best_5k_s": 1861, "best_10k_s": null,
  "photo_url": "http://…:9000/runtrail-photos/runs/…jpg?X-Amz-…",   // ลิงก์ชั่วคราว 1 ชม.
  "share": { "code": "PY6P3N2S", "url": "http://…:8080/s/PY6P3N2S", "hide_ends": true, "show_photo": true, "show_kcal": false },
  "client_updated_at": "…", "updated_at": "…",
  "splits": [ { "km": 1, "pace_s": 371, "elev_delta_m": 3 }, … ]
}
```
`verify_reason` ที่เป็นไปได้: `manual`, `too_few_points` (< 30 จุด), `sparse_points` (จุดห่างเกิน 10 วิ), `distance_mismatch` (ต่างจากแอปเกิน 5%), `moving_time_mismatch`, `too_fast` (เพซเฉลี่ยเร็วกว่า 2:50/กม.), `too_fast_segment` (เร็วกว่า 7 ม./วิ ต่อเนื่อง 30 วิ), `future_start`, `no_distance`

**`PATCH /runs/{uuid}`** `{ "title"?, "note"?, "started_at"?, "timezone"?, "client_updated_at" }` — แก้จากหน้า Edit
- การวิ่ง GPS แก้ `started_at` ไม่ได้ (400 `gps_locked`) กันการย้ายการวิ่งข้ามสัปดาห์ในอันดับ

**`DELETE /runs/{uuid}`** → 204 เสมอ (ลบซ้ำ / ไม่เคยซิงก์ก็ 204) · ลบรูปและลิงก์แชร์ด้วย

**`GET /runs/{uuid}`** · **`GET /runs/{uuid}/track`** → `{ "run_id", "point_count", "bbox", "points" }`

**`GET /runs?updated_since=<เวลา>&cursor=<…>&limit=50`** — ดึงการเปลี่ยนแปลงลงเครื่อง
```json
{ "items": [ { …run… }, { "id": "…", "deleted": true, "updated_at": "…" } ],
  "next_cursor": "MjAyNi0…", "server_time": "2026-11-10T05:20:41Z" }
```
- ไม่ส่ง `updated_since` = ดึงทั้งหมด (ล็อกอินเครื่องใหม่) ไม่รวมที่ถูกลบ
- ส่ง `updated_since` = เฉพาะที่เปลี่ยน **รวมรายการที่ถูกลบ** (`deleted: true`) → ลบในเครื่องตาม
- มี `next_cursor` → เรียกซ้ำด้วย `updated_since` เดิม + `cursor` จนกว่าจะเป็น `null`
- เก็บ `server_time` ของรอบแรกไว้เป็น `updated_since` ของรอบหน้า (server ถอยเวลาให้ 1 นาทีกันตกหล่น
  จึงอาจได้รายการเดิมซ้ำ → รวมด้วย UUID และเลือกอันที่ `client_updated_at` ใหม่กว่า)

### รูปภาพ 🔒 (มือถืออัปโหลดตรงไป S3)
1. `POST /runs/{uuid}/photo/upload-url` `{ "content_type": "image/jpeg", "size": 183422 }`
   → `{ "upload_url": "…", "method": "PUT", "headers": { "Content-Type": "image/jpeg" }, "key": "runs/…/….jpg", "expires_in": 600 }`
   (รองรับ `image/jpeg`, `image/webp` · ไม่เกิน 5 MB · การวิ่งต้องซิงก์ขึ้นไปก่อน)
2. `PUT <upload_url>` พร้อม header ใน `headers` และ body เป็นไฟล์รูป (ไม่ต้องส่ง Authorization)
3. `POST /runs/{uuid}/photo/confirm` `{ "key" }` → การวิ่งพร้อม `photo_url` · ยังไม่ได้อัปโหลด → 400 `upload_missing`
- `DELETE /runs/{uuid}/photo` → 204 · อัปโหลดแล้วไม่ confirm เกิน 24 ชม. → ระบบลบไฟล์ทิ้งเอง

### อันดับโลก 🔒
**`GET /leaderboard?metric=distance&period=week&scope=global&limit=50`**
- `metric`: `distance` (ระยะรวม, ค่าเป็นเมตร มากไปน้อย) · `best_5k` · `best_10k` (วินาที น้อยไปมาก)
- `period`: `week` (จันทร์ 00:00 UTC = 07:00 ไทย) · `month` · `all`
- `scope`: `global` · `country` (ใช้ประเทศในโปรไฟล์ หรือส่ง `country=TH`)
```json
{ "metric": "distance", "period": "week", "scope": "global", "country": null,
  "period_start": "2026-11-09T00:00:00Z", "period_end": "2026-11-16T00:00:00Z", "total": 3,
  "entries": [ { "rank": 1, "user_id": "…", "display_name": "Ben", "avatar_url": null, "country_code": "TH", "value": 7141.2, "is_me": false } ],
  "me": { "rank": 3, …, "is_me": true } }
```
**`GET /leaderboard/me?metric=&period=&scope=`** → `{ "me", "ahead", "behind", "total", … }`

นับเฉพาะ: GPS + ผ่านการตรวจ (`verified`) + ไม่ถูกลบ + เจ้าของเปิด `leaderboard_opt_in` · cache 60 วินาที

### แชร์
| | |
|---|---|
| `POST /runs/{uuid}/share` 🔒 | `{ "hide_ends"?, "show_photo"?, "show_kcal"? }` (body ว่างได้) → `{ "code", "url", … }` · เรียกซ้ำ = รหัสเดิม แก้ตัวเลือก |
| `DELETE /runs/{uuid}/share` 🔒 | ยกเลิกลิงก์ทันที |
| `GET /public/runs/{code}` | ไม่ต้อง login · จุดในรัศมี 200 ม. จากจุดเริ่ม/จบถูกตัดที่ server แล้ว · ไม่มีเวลาของแต่ละจุด · ไม่มีโน้ต |
| `GET /s/{code}` | หน้าเว็บ (Leaflet + แผนที่ CARTO) สำหรับเปิดในเบราว์เซอร์/WebView · มี OG tag ให้พรีวิวใน LINE/Facebook |

```json
// GET /public/runs/{code}
{ "code": "PY6P3N2S", "title": "…", "runner_name": "Ploy", "runner_avatar_url": null, "source": "gps",
  "started_at": "…", "timezone": "Asia/Bangkok", "distance_m": 2404.1, "moving_time_s": 802, "pace_s_per_km": 334,
  "elevation_gain_m": 2, "calories": 160, "photo_url": "…", "verified": true, "hide_ends": true,
  "splits": [ … ], "segments": [ [ [13.72981, 100.54012, 333], … ] ] }   // [lat, lng, pace วินาที/กม.]
```

---

## Test

- `internal/track` — สูตรตรงกับแอป, splits, best effort, ตัด 200 ม., ตรวจโกงทุกเหตุผล
- `internal/auth` — argon2id, JWT (หมดอายุ, ผิด secret, alg none), refresh token, share code
- `internal/api` — integration test กับ Postgres จริง: สมัคร/ล็อกอิน, refresh rotation + grace + reuse, logout, เปลี่ยนรหัสผ่าน,
  rate limit, PUT ซ้ำไม่ซ้ำแถว, last-write-wins, UUID ของคนอื่น, validation, gzip + gzip bomb, sync list + tombstone + cursor,
  รูป (ก่อน/หลังอัปโหลด, แทนที่, cleanup), อันดับ (manual/เร็วผิดปกติ/สัปดาห์ก่อนไม่นับ, ประเทศ, opt-out), แชร์ซ่อนบ้าน, ลบบัญชี
- `scripts/smoke.sh` — ทั้ง flow กับ Docker จริง รวมอัปโหลดรูปเข้า RustFS และ body 7 MB → 413

## ขึ้น AWS
ยังไม่ได้ทำในงานนี้ (แผนข้อ 6) — image เดียวกันนี้รันบน EC2 ได้ โดยเปลี่ยน env: ไม่ตั้ง `S3_ENDPOINT` / `S3_ACCESS_KEY` (ใช้ IAM Role),
`S3_PATH_STYLE=false`, `PUBLIC_BASE_URL=https://<โดเมน>`, `PROXY_HEADER=X-Forwarded-For` เมื่ออยู่หลัง Caddy
