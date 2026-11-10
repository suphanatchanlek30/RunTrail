# RunTrail — แผนระบบ Backend + Database + การเชื่อมแอป

> สถานะ: **แผน (ยังไม่ลงมือ)** · เขียนเมื่อ 10 พ.ย. 2026
> Frontend: Kotlin + XML + ViewBinding (ตามที่อาจารย์อนุญาต — โปรเจคตอนนี้เป็นแบบนี้อยู่แล้ว 100%)
> Backend: Go + Fiber v3 (ล่าสุด v3.7.0) · PostgreSQL · S3 (MinIO ตอนรันในเครื่อง) · Docker · Terraform บน AWS

---

## 0. หลักการสำคัญ (อ่านก่อน)

### 0.1 Offline-first แบบ Strava — ข้อนี้สำคัญที่สุด
1. **กดเริ่มวิ่งได้เสมอ** แม้ไม่มีเน็ต / โหมดเครื่องบิน GPS ไม่ต้องใช้อินเทอร์เน็ต
2. **ฐานข้อมูลในเครื่อง (SQLite) คือแหล่งข้อมูลหลัก** หน้าจอทุกหน้าอ่านจาก SQLite เท่านั้น ไม่รอ server
3. ทุกการเปลี่ยนแปลง (สร้าง / แก้ / ลบ / แนบรูป) ถูกบันทึกในเครื่องก่อน แล้วติดสถานะ `PENDING`
4. **WorkManager** คอยส่งขึ้น server อัตโนมัติเมื่อมีเน็ต (`NetworkType.CONNECTED`) ล้มเหลวก็ลองใหม่แบบ backoff
5. ส่งซ้ำกี่ครั้งก็ไม่เกิดข้อมูลซ้ำ เพราะการวิ่งแต่ละรอบมี **UUID ที่สร้างในเครื่อง** และ server ใช้ `PUT /runs/{uuid}` (upsert)
6. ฟีเจอร์ที่ต้องใช้เน็ตจริง ๆ มีแค่: สมัคร/ล็อกอินครั้งแรก · ดูอันดับโลก · สร้างลิงก์แชร์ · โหลดภาพแผนที่ที่ยังไม่เคยเปิด
   - ไม่มีเน็ต → แสดงข้อความว่า "ออฟไลน์ — จะซิงก์เมื่อต่อเน็ต" และใช้ข้อมูลล่าสุดที่ cache ไว้ (เช่น อันดับล่าสุด)
7. ภาพแผนที่: osmdroid cache tile ที่เคยเปิดไว้ในเครื่อง ถ้าไม่มี tile เส้นทาง GPS ก็ยังวาดและบันทึกได้ปกติ (พื้นหลังจะเป็นตารางเปล่า)
   - เพิ่มปุ่ม "ดาวน์โหลดแผนที่รอบตัว" (osmdroid `CacheManager`) ให้โหลด tile รัศมี ~3 กม. ไว้ก่อนออกวิ่ง (ทำใน Phase 6)

### 0.2 ล็อกอินกับออฟไลน์
- ล็อกอินครั้งแรกต้องใช้เน็ต หลังจากนั้นเปิดแอปออฟไลน์ได้ตลอด (เก็บ token + โปรไฟล์ในเครื่อง)
- มีปุ่ม **"ใช้งานแบบออฟไลน์ก่อน"** (Guest) ในหน้า Welcome → วิ่งและบันทึกได้ครบ การวิ่งจะรอค้างใน `PENDING` และอัปโหลดให้อัตโนมัติเมื่อผู้ใช้สมัคร/ล็อกอินภายหลัง
- Access token หมดอายุระหว่างออฟไลน์ไม่เป็นไร แอปไม่ต้องใช้ token จนกว่าจะซิงก์ ตอนซิงก์จะ refresh ให้เอง
- Refresh token หมดอายุ (30 วัน) → ให้ล็อกอินใหม่ **ข้อมูลในเครื่องไม่หาย**

### 0.3 Server ไม่เชื่อตัวเลขจากแอป
- Server คำนวณระยะทาง/เวลา/เพซใหม่จากจุด GPS ที่ส่งมา ใช้ตัวเลขของ server ในอันดับโลก
- การวิ่งที่เพิ่มเอง (ไม่มี GPS) **ไม่เข้าอันดับ**
- ความเป็นส่วนตัว (ซ่อน 200 ม. แรก/สุดท้าย, ซ่อนรูป, ซ่อนแคลอรี่) **ตัดที่ server** ก่อนส่งให้คนอื่น ไม่ใช่แค่ซ่อนในหน้าเว็บ

---

## 1. ภาพรวมสถาปัตยกรรม

```
┌───────────── Android (Kotlin + XML) ─────────────┐
│ Activity (ViewBinding)                           │
│   │ อ่าน/เขียน                                     │
│   ▼                                              │
│ RunRepository ──► SQLite (RunTrailDb)            │ ◄── แหล่งข้อมูลหลัก ใช้ได้ออฟไลน์
│   │                 runs · run_points · outbox   │
│   ▼                                              │
│ RunTrackingService (Foreground, type=location)   │ ◄── GPS ทำงานตอนปิดจอ
│ SyncWorker (WorkManager, ต้องมีเน็ต)              │
│   │ OkHttp + JWT (Authenticator refresh อัตโนมัติ)  │
└───┼──────────────────────────────────────────────┘
    │ HTTPS  /api/v1/...
    ▼
┌──────────── Go Fiber API (Docker) ───────────────┐
│ auth · users · runs · photos · leaderboard · share│
│   │                    │                         │
│   ▼                    ▼                         │
│ PostgreSQL 17     S3 / MinIO (presigned URL)     │
└──────────────────────────────────────────────────┘
       หน้าเว็บแชร์ https://<host>/s/{code}  (server render + Leaflet)
```

รูปขึ้น S3 **ตรงจากมือถือ** ด้วย presigned URL (ไม่ผ่าน Go) — server แค่ออก URL และยืนยันว่าอัปโหลดเสร็จ

---

## 2. Backend (Go Fiber)

### 2.1 เทคโนโลยี
| ส่วน | เลือก | เหตุผล |
|---|---|---|
| Web framework | `github.com/gofiber/fiber/v3` v3.7.x | ตามที่ต้องการ |
| DB driver | `jackc/pgx/v5` (pgxpool) | เร็ว รองรับ JSONB |
| Query | `sqlc` (สร้างโค้ดจาก SQL) | type-safe ไม่ต้องใช้ ORM |
| Migration | `pressly/goose/v3` ฝัง SQL ใน binary | รัน migrate อัตโนมัติตอน start |
| Password | `golang.org/x/crypto/argon2` (argon2id) | มาตรฐานปัจจุบัน |
| JWT | `golang-jwt/jwt/v5` (HS256) | access token 15 นาที |
| S3 | `aws-sdk-go-v2/service/s3` + presign | ใช้ได้ทั้ง MinIO และ AWS S3 |
| Config | env vars (`caarlos0/env`) | 12-factor รันใน Docker ง่าย |
| Log | `log/slog` (JSON) | ในตัว Go |
| Validation | `go-playground/validator/v10` | ตรวจ body |
| Test | `testing` + `testcontainers-go` (Postgres จริง) | |

### 2.2 โครงสร้างโฟลเดอร์ (อยู่ใน repo นี้ที่ `backend/`)
```
backend/
├── cmd/api/main.go              # อ่าน config → ต่อ DB → migrate → start Fiber
├── internal/
│   ├── config/                  # env
│   ├── db/
│   │   ├── migrations/*.sql     # goose
│   │   ├── queries/*.sql        # sqlc
│   │   └── gen/                 # โค้ดที่ sqlc สร้าง
│   ├── auth/                    # hash, jwt, refresh token, middleware
│   ├── user/                    # handler + service
│   ├── run/                     # handler + service + track (คำนวณใหม่, ตัดปลาย, ตรวจโกง)
│   ├── photo/                   # presign + confirm
│   ├── leaderboard/
│   ├── share/                   # API + หน้าเว็บ /s/{code} (html/template + Leaflet)
│   ├── storage/                 # S3 client (internal endpoint + public endpoint)
│   └── httpx/                   # error format, pagination, middleware ร่วม
├── web/templates/share.html
├── Dockerfile                   # multi-stage → distroless, user nonroot
├── docker-compose.yml
├── .env.example
├── Makefile                     # make up / down / test / sqlc / migrate
└── README.md
```

### 2.3 Database schema (PostgreSQL)

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email           CITEXT UNIQUE NOT NULL,
  password_hash   TEXT NOT NULL,
  display_name    TEXT NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 40),
  first_name      TEXT, last_name TEXT,
  country_code    CHAR(2),                     -- ใช้กรองอันดับรายประเทศ
  avatar_key      TEXT,                        -- key ใน S3
  weight_kg       SMALLINT CHECK (weight_kg BETWEEN 20 AND 300),
  height_cm       SMALLINT CHECK (height_cm BETWEEN 80 AND 250),
  birth_year      SMALLINT,
  use_miles       BOOLEAN NOT NULL DEFAULT false,
  weekly_goal_km  SMALLINT NOT NULL DEFAULT 20,
  target_pace_s   SMALLINT NOT NULL DEFAULT 360,
  leaderboard_opt_in BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);

CREATE TABLE refresh_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  BYTEA NOT NULL UNIQUE,          -- sha256 ของ token (ไม่เก็บตัวจริง)
  family_id   UUID NOT NULL,                  -- ตรวจ token ถูกขโมย (reuse detection)
  device_name TEXT,
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE runs (
  id               UUID PRIMARY KEY,           -- ★ UUID ที่แอปสร้าง → ส่งซ้ำไม่ซ้ำ
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title            TEXT NOT NULL,
  note             TEXT,
  source           TEXT NOT NULL CHECK (source IN ('gps','manual')),
  started_at       TIMESTAMPTZ NOT NULL,
  ended_at         TIMESTAMPTZ,
  timezone         TEXT,                       -- เช่น Asia/Bangkok ไว้ตัดวัน/สัปดาห์ให้ตรง
  -- ค่าที่แอปส่งมา (แสดงผล)
  client_distance_m DOUBLE PRECISION NOT NULL,
  moving_time_s     INTEGER NOT NULL,
  calories          DOUBLE PRECISION,
  elevation_gain_m  INTEGER,
  cadence_spm       INTEGER,
  temp_c            SMALLINT,                  -- null = ไม่มีเซนเซอร์ (ห้ามใส่ค่าปลอม)
  -- ค่าที่ server คำนวณเอง (ใช้จัดอันดับ)
  distance_m        DOUBLE PRECISION,
  verified          BOOLEAN NOT NULL DEFAULT false,
  verify_reason     TEXT,                      -- เหตุผลที่ไม่ผ่าน เช่น 'too_fast'
  best_1k_s INTEGER, best_5k_s INTEGER, best_10k_s INTEGER,
  -- แชร์
  share_code        TEXT UNIQUE,               -- 8 ตัว [A-Z2-9] สุ่มที่ server
  share_hide_ends   BOOLEAN NOT NULL DEFAULT true,
  share_show_photo  BOOLEAN NOT NULL DEFAULT true,
  share_show_kcal   BOOLEAN NOT NULL DEFAULT false,
  photo_key         TEXT,
  client_updated_at TIMESTAMPTZ NOT NULL,      -- last-write-wins
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ                -- soft delete ให้เครื่องอื่นรู้ว่าถูกลบ
);
CREATE INDEX runs_user_started ON runs (user_id, started_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX runs_user_updated ON runs (user_id, updated_at);
CREATE INDEX runs_lb ON runs (started_at) WHERE verified AND source = 'gps' AND deleted_at IS NULL;

-- จุด GPS เก็บเป็นก้อนเดียวต่อการวิ่ง (1 ชม. ≈ 3,600 จุด ≈ 150 KB) อ่าน/เขียนทีเดียว
CREATE TABLE run_tracks (
  run_id     UUID PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE,
  points     JSONB NOT NULL,      -- [[lat,lng,t_ms,alt,acc], ...]
  point_count INTEGER NOT NULL,
  bbox       DOUBLE PRECISION[4]  -- minLat,minLng,maxLat,maxLng
);

CREATE TABLE run_splits (
  run_id   UUID REFERENCES runs(id) ON DELETE CASCADE,
  km       SMALLINT,
  pace_s   INTEGER NOT NULL,
  elev_delta_m SMALLINT,
  PRIMARY KEY (run_id, km)
);

CREATE TABLE photo_uploads (                 -- รูปที่ขอ URL แล้วแต่ยังไม่ยืนยัน
  key        TEXT PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  run_id     UUID,                           -- null = รูปโปรไฟล์
  expires_at TIMESTAMPTZ NOT NULL,
  confirmed  BOOLEAN NOT NULL DEFAULT false
);
```

### 2.4 API (prefix `/api/v1`, JSON, เวลาเป็น ISO-8601 UTC)

รูปแบบ error เดียวกันทุก endpoint:
```json
{ "error": { "code": "validation_failed", "message": "อีเมลไม่ถูกต้อง", "fields": { "email": "invalid" } } }
```

#### Auth
| Method | Path | Body / หมายเหตุ | ตอบกลับ |
|---|---|---|---|
| POST | `/auth/register` | `{email, password(≥8), display_name, country_code?}` | 201 `{user, access_token, refresh_token, expires_in}` · 409 email ซ้ำ |
| POST | `/auth/login` | `{email, password, device_name}` | 200 แบบเดียวกัน · 401 |
| POST | `/auth/refresh` | `{refresh_token}` → **หมุน token ใหม่ทุกครั้ง** ใช้ token เก่าซ้ำ = เพิกถอนทั้ง family · ยกเว้นช่วงผ่อนผัน 30 วิ: token ก่อนหน้าทันทีได้คู่ใหม่ชุดเดิมกลับไป (กัน UI กับ SyncWorker refresh พร้อมกันแล้วโดนเตะออก) | 200 |
| POST | `/auth/logout` | `{refresh_token}` | 204 |
| POST | `/auth/change-password` | `{old_password, new_password}` (ต้อง login) | 204 + เพิกถอน token อื่นทั้งหมด |

- จำกัดความถี่: login/register 10 ครั้ง/นาที/IP (Fiber `limiter`)
- ลืมรหัสผ่านทางอีเมล (ต้องใช้ AWS SES + โดเมน) → ทำเป็นงานเสริมท้ายสุด

#### ผู้ใช้
| Method | Path | หมายเหตุ |
|---|---|---|
| GET | `/me` | โปรไฟล์ + ค่าตั้งค่า |
| PATCH | `/me` | แก้ชื่อ น้ำหนัก ส่วนสูง เป้าหมาย หน่วย ประเทศ เข้าร่วมอันดับไหม |
| POST | `/me/avatar/upload-url` | ได้ presigned PUT สำหรับรูปโปรไฟล์ |
| DELETE | `/me` | ลบบัญชี (soft delete + ลบรูปใน S3 ด้วย job) |
| GET | `/me/stats` | ระยะ/เวลา/แคลอรี่รวม · PR 1/5/10K · รายเดือน (ใช้ตอน restore เครื่องใหม่) |

#### การวิ่ง (หัวใจของการซิงก์)
| Method | Path | หมายเหตุ |
|---|---|---|
| PUT | `/runs/{uuid}` | **Upsert** — ถ้า UUID นี้เป็นของผู้ใช้อื่น ตอบ **409 ไม่เขียนทับเด็ดขาด** (เช็คใน `ON CONFLICT ... WHERE runs.user_id = $user`) · body: ข้อมูลการวิ่ง + `points` + `client_updated_at` · gzip ได้ (`Content-Encoding: gzip`) · ถ้า server มีอันใหม่กว่า (`client_updated_at` มากกว่า) ตอบ 200 พร้อมตัวของ server ไม่เขียนทับ · สูงสุด 5 MB หลังคลาย gzip (จำกัดทั้งขนาดที่ส่งมาและขนาดหลังคลาย กัน gzip bomb) / 50,000 จุด |
| PATCH | `/runs/{uuid}` | แก้ชื่อ โน้ต เวลาเริ่ม ตัวเลือกแชร์ (ไม่ส่งจุด GPS ซ้ำ) |
| DELETE | `/runs/{uuid}` | soft delete · ลบซ้ำ = 204 เหมือนเดิม (idempotent) |
| GET | `/runs?updated_since=&cursor=&limit=50` | ดึงของใหม่/ที่ถูกลบ (มี `deleted:true`) — ใช้ตอนล็อกอินเครื่องใหม่ |
| GET | `/runs/{uuid}` | รายละเอียด + splits |
| GET | `/runs/{uuid}/track` | จุด GPS ทั้งหมด (เจ้าของเท่านั้น) |

ตัวอย่าง body ของ `PUT /runs/{uuid}`:
```json
{
  "title": "วิ่งเย็นริมสวน", "note": null, "source": "gps",
  "started_at": "2026-11-10T11:02:10Z", "ended_at": "2026-11-10T11:34:00Z",
  "timezone": "Asia/Bangkok",
  "distance_m": 5020.4, "moving_time_s": 1868, "calories": 342,
  "elevation_gain_m": 12, "cadence_spm": 168, "temp_c": null,
  "points": [[13.7290, 100.5385, 1762772530000, 5.0, 6.0], "..."],
  "client_updated_at": "2026-11-10T11:34:05Z"
}
```

สิ่งที่ server ทำตอนรับการวิ่ง GPS:
1. เรียงจุดตามเวลา ตัดจุดที่ `acc > 25 ม.` (กฎเดียวกับ `GpsFilter` ในแอป)
2. คำนวณ `distance_m` ใหม่ด้วย Haversine · splits · best 1/5/10K
3. **ตรวจความน่าเชื่อ** → `verified = true` เมื่อผ่านทั้งหมด:
   - ระยะ server ต่างจากแอปไม่เกิน 5%
   - เพซเฉลี่ยไม่เร็วกว่า 2:50/กม. และไม่มีช่วง 30 วิที่ความเร็ว > 9 ม./วิ
   - จุดเฉลี่ยห่างกันไม่เกิน 10 วิ (ไม่ใช่เส้นที่วาดเอง) · มีอย่างน้อย 30 จุด
   - เวลาเริ่มไม่อยู่ในอนาคต
4. ไม่ผ่าน → ยังบันทึกได้ปกติ แต่ไม่เข้าอันดับ และส่ง `verify_reason` กลับให้แอปแสดง

#### รูปภาพ (S3 presigned)
| Method | Path | หมายเหตุ |
|---|---|---|
| POST | `/runs/{uuid}/photo/upload-url` | `{content_type:"image/jpeg", size}` → `{upload_url, key, expires_in:600}` · จำกัด 5 MB · jpeg/webp เท่านั้น |
| (มือถือ) PUT | `upload_url` | ส่งไฟล์ตรงไป S3/MinIO |
| POST | `/runs/{uuid}/photo/confirm` | `{key}` → server `HeadObject` ตรวจว่ามีจริง แล้วผูกกับการวิ่ง |
| DELETE | `/runs/{uuid}/photo` | ลบรูป |

- Bucket เป็น **private** · อ่านรูปผ่าน presigned GET อายุ 1 ชม. ที่ server แนบมาใน response (`photo_url`)
- Key: `runs/{user_id}/{run_id}/{random}.jpg` · `avatars/{user_id}/{random}.jpg`
- MinIO ใน Docker: server ต้องมี 2 endpoint — `S3_ENDPOINT=http://minio:9000` (server คุยเอง) และ `S3_PUBLIC_ENDPOINT=http://<IP เครื่อง Mac>:9000` (ใช้เซ็น URL ให้มือถือ) ไม่งั้นมือถือจะต่อ `minio:9000` ไม่ได้
  - ต้องสร้าง S3 client ตัวที่ 2 สำหรับเซ็น URL ด้วย `S3_PUBLIC_ENDPOINT` + `UsePathStyle: true` (ลายเซ็นผูกกับ host จึงเซ็นกับ `minio:9000` แล้วค่อยเปลี่ยน host ทีหลังไม่ได้)
- งานเก็บกวาด: ทุกชั่วโมงลบ object ที่ขอ URL แล้วไม่ confirm เกิน 24 ชม.

#### อันดับโลก (Leaderboard)
| Method | Path | หมายเหตุ |
|---|---|---|
| GET | `/leaderboard?metric=distance&period=week&scope=global&limit=50` | `metric`: `distance` (ระยะรวม) · `best_5k` · `best_10k` · `period`: `week` · `month` · `all` · `scope`: `global` · `country` |
| GET | `/leaderboard/me?metric=&period=` | อันดับของฉัน + คนก่อนหน้า/ถัดไป |

- นับเฉพาะ `verified AND source='gps' AND deleted_at IS NULL AND users.leaderboard_opt_in`
- ช่วงเวลา: **สัปดาห์ = ISO week ตามเวลา UTC** (จันทร์ 00:00 UTC = 07:00 ไทย) · เดือน = เดือนปฏิทิน UTC — ทุกคนทั่วโลกใช้ขอบเขตเดียวกัน แสดงในแอปว่า "รีเซ็ตทุกวันจันทร์ 07:00"
- Query ด้วย SQL `SUM ... GROUP BY user_id ORDER BY ... ` + `RANK()` พร้อม cache ในหน่วยความจำ 60 วินาที (ผู้ใช้ระดับโปรเจคนี้เพียงพอ ไม่ต้องใช้ Redis)
- ตอบกลับ: `{period_start, period_end, entries:[{rank, user_id, display_name, avatar_url, country_code, value}], me:{rank,value}}`

#### แชร์
| Method | Path | หมายเหตุ |
|---|---|---|
| POST | `/runs/{uuid}/share` | `{hide_ends, show_photo, show_kcal}` → สร้าง/อัปเดต `share_code` ตอบ `{code, url}` (**URL มาจาก server** ใช้ `PUBLIC_BASE_URL`) |
| DELETE | `/runs/{uuid}/share` | ยกเลิกลิงก์ (ลิงก์เก่าตอบ 404 ทันที) |
| GET | `/public/runs/{code}` | ไม่ต้อง login · จุด GPS **ถูกตัด 200 ม. แรก/สุดท้ายที่ server แล้ว** · ไม่มีรูป/แคลอรี่ถ้าปิดไว้ |
| GET | `/s/{code}` (ไม่มี prefix api) | หน้าเว็บ HTML render ที่ server · Leaflet + OSM/CARTO · เส้นไล่สีตามเพซ · OG meta tag ให้พรีวิวสวยใน LINE/Facebook |

#### อื่น ๆ
- `GET /healthz` (process ยังทำงาน) · `GET /readyz` (ต่อ DB + S3 ได้)
- Middleware: `recover` · `requestid` · `logger` (slog) · `cors` (เฉพาะหน้าเว็บ) · `limiter` · body limit 6 MB · timeout 15 วิ
- Graceful shutdown เมื่อได้ SIGTERM

### 2.5 Docker (รันในเครื่องก่อน)
`backend/docker-compose.yml` มี:
| service | image | พอร์ต |
|---|---|---|
| `db` | `postgres:17-alpine` + volume + healthcheck `pg_isready` | 5432 |
| `minio` | `minio/minio` + volume | 9000 (API) · 9001 (console) |
| `minio-init` | `minio/mc` สร้าง bucket `runtrail-photos` แล้วจบ | — |
| `api` | build จาก `Dockerfile` · `depends_on: db (healthy)` | 8080 |

```bash
cd backend
cp .env.example .env        # แก้ S3_PUBLIC_ENDPOINT เป็น IP ของ Mac (ดูด้วย: ipconfig getifaddr en0)
docker compose up -d --build
curl localhost:8080/readyz
```
แอปบน Emulator ใช้ `http://10.0.2.2:8080` · มือถือจริงใช้ `http://<IP Mac>:8080` (ต้องอยู่ Wi-Fi เดียวกัน)

---

## 3. ฝั่งแอป Android — สิ่งที่ต้องเปลี่ยน

### 3.1 ไลบรารีที่เพิ่ม (ไม่มี annotation processor → ไม่ต้องพึ่ง KSP กับ AGP 9)
| ไลบรารี | ใช้ทำ |
|---|---|
| `SQLiteOpenHelper` (ในตัว Android) | ฐานข้อมูลในเครื่อง — ตรงกับ MA10 ที่เรียน |
| `androidx.work:work-runtime-ktx` | ซิงก์อัตโนมัติเมื่อมีเน็ต |
| `com.squareup.okhttp3:okhttp` + `org.json` (ในตัว) | เรียก API · `Authenticator` refresh token อัตโนมัติ (ใช้ OkHttpClient ตัวเดียวทั้งแอป + lock ตอน refresh และเช็คซ้ำว่า token เปลี่ยนแล้วหรือยังหลังได้ lock) · ทำงานแบบ sync ใน Worker ได้ |
| `androidx.security` ❌ ไม่ใช้ (deprecated) | เก็บ refresh token ด้วย Android Keystore เข้ารหัส AES-GCM เอง (`TokenStore`) |
| `androidx.lifecycle:lifecycle-service` | Foreground service |

> ถ้าอาจารย์บังคับ Volley (MA11) ใช้ Volley ในหน้าจอได้ แต่ Worker ควรใช้ OkHttp เพราะต้องเรียกแบบรอผล (synchronous)

### 3.2 ฐานข้อมูลในเครื่อง (SQLite `runtrail.db`)
```sql
runs(
  local_id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT UNIQUE NOT NULL,          -- สร้างตอนเริ่มวิ่ง UUID.randomUUID()
  owner_user_id TEXT,                 -- null = Guest
  title, note, source('gps'|'manual'), start_time, end_time, timezone,
  distance_m, moving_time_s, calories, elevation_gain_m, cadence_spm, temp_c (nullable),
  photo_path TEXT, photo_key TEXT,    -- path ในเครื่อง / key บน S3
  share_code, share_url, share_hide_ends, share_show_photo, share_show_kcal,
  verified INTEGER, verify_reason TEXT,
  sync_state TEXT NOT NULL,           -- PENDING | SYNCING | SYNCED | FAILED
  photo_sync_state TEXT,              -- NONE | PENDING | SYNCED
  deleted INTEGER DEFAULT 0,          -- tombstone รอส่ง DELETE
  updated_at INTEGER NOT NULL, server_updated_at INTEGER
)
run_points(run_local_id, seq, lat, lng, time, alt, acc, PRIMARY KEY(run_local_id, seq))
splits(run_local_id, km, pace_s, elev_delta_m)
```
- `RunRepository` คงชื่อเมธอดเดิม (`getAll / getById / insert / update / delete / query`) แต่ข้างในอ่าน SQLite → หน้าจอแก้น้อยที่สุด
- ลบ `SampleData` ออกจากการโหลดจริง (เก็บไว้เป็น debug เท่านั้น)
- **การวิ่งที่ค้าง (draft) ย้ายจากไฟล์ .properties/CSV ไปเป็นแถวใน `runs` ที่ `end_time IS NULL`** และเขียนจุด GPS ลง `run_points` ทุก 5 วิ → กู้คืนได้ครบทุกจุด

### 3.3 Foreground Service สำหรับวิ่ง (แก้ปัญหา GPS หยุดตอนปิดจอ)
- `RunTrackingService` (`foregroundServiceType="location"`) เป็นเจ้าของสถานะการวิ่งทั้งหมด: track, ระยะ, เวลา, auto-pause, cadence
- `LiveRunActivity` แค่ bind เข้าไปอ่านค่ามาแสดง (ปิดจอ/สลับแอป/หมุนจอ ก็ไม่หลุด)
- การแจ้งเตือนค้าง: "กำลังวิ่ง · 3.42 กม. · 21:15" พร้อมปุ่มหยุดชั่วคราว
- **นับเวลาจาก `SystemClock.elapsedRealtime()`** ไม่ใช่นับจำนวน `tick()` (ตอนนี้ถ้า Handler ช้าหรือแอปถูกพัก เวลาจะเพี้ยน)
- ใช้เวลาของจุด GPS จาก `location.elapsedRealtimeNanos` แปลงเป็น epoch
- Manifest เพิ่ม: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`, `<service android:foregroundServiceType="location">`
- เอา `FLAG_KEEP_SCREEN_ON` ออก (ประหยัดแบต)
- ถ้า GPS provider ปิด ใช้ `NETWORK_PROVIDER` สำรอง (ไม่ต้องใช้ Google Play Services)

### 3.4 การซิงก์ (`SyncWorker`)
ลำดับในแต่ละรอบ:
1. ไม่มี token (Guest) → จบ รอจนล็อกอิน · ซิงก์**เฉพาะแถวที่ `owner_user_id = ผู้ใช้ปัจจุบัน`** (กันการวิ่งของ A ขึ้นบัญชี B เมื่อสลับบัญชีในเครื่องเดียว)
   - ตอนล็อกอิน ถามแล้วกำหนดเจ้าของให้การวิ่ง Guest (`owner_user_id IS NULL`) เป็นผู้ใช้ที่ล็อกอิน
2. ส่ง tombstone → `DELETE /runs/{uuid}`
3. การวิ่ง `PENDING/FAILED` ที่จบแล้ว → `PUT /runs/{uuid}` (gzip) → บันทึก `verified`, `distance_m` ของ server, เปลี่ยนเป็น `SYNCED`
4. รูป `photo_sync_state = PENDING` → ขอ URL → PUT ไป S3 → confirm
5. ดึง `GET /runs?updated_since=<ครั้งล่าสุด>` มารวม (เครื่องใหม่ / แก้จากที่อื่น)
- ทริกเกอร์: จบการวิ่ง · แก้/ลบ · เปิดแอป · ทุก 6 ชม. (periodic) — ทั้งหมดมี constraint `CONNECTED`
- ล้มเหลว: 5xx/เน็ตหลุด → `Result.retry()` (exponential backoff) · 4xx → `FAILED` + แสดงเหตุผลในการ์ด
- ปุ่ม "ซิงก์ตอนนี้" ใน ShareSheet = `WorkManager.enqueueUniqueWork(..., KEEP)`
- ป้ายสถานะในการ์ด (มีอยู่แล้ว): ☁️✓ ซิงก์แล้ว · ☁️↑ รอซิงก์ · ↻ ล้มเหลว — เพิ่มแถบ "ออฟไลน์ · รอส่ง 3 รายการ" ในหน้าหลัก

### 3.5 หน้าจอใหม่ / ที่แก้
| หน้าจอ | สิ่งที่ทำ |
|---|---|
| **ใหม่** `LoginActivity` | อีเมล + รหัสผ่าน · แสดง/ซ่อนรหัส · error ใต้ช่อง · ปุ่ม "ใช้แบบออฟไลน์ก่อน" |
| **ใหม่** `RegisterActivity` | ชื่อที่แสดง · อีเมล · รหัสผ่าน + ยืนยัน · ประเทศ · ยอมรับนโยบาย |
| **ใหม่** `LeaderboardActivity` (แท็บที่ 5 "อันดับ") | Segment: สัปดาห์/เดือน/ทั้งหมด · Chip: ระยะรวม/5K/10K · ทั่วโลก/ประเทศ · RecyclerView + การ์ด "อันดับของฉัน" ติดล่าง · pull-to-refresh · ออฟไลน์แสดงข้อมูล cache + เวลาอัปเดตล่าสุด |
| Welcome → Permissions → **Login/Register** → ProfileSetup | ProfileSetup ส่ง `PATCH /me` (ออฟไลน์ก็เก็บในเครื่องก่อน) |
| Settings | เพิ่ม: บัญชี (อีเมล) · เปลี่ยนรหัสผ่าน · เข้าร่วมอันดับ on/off · ออกจากระบบ (ถามว่าจะลบข้อมูลในเครื่องไหม ถ้ามีรายการรอซิงก์ต้องเตือน) · ลบบัญชี · "ที่อยู่เซิร์ฟเวอร์" เปลี่ยนเป็น URL เต็ม เช่น `http://10.0.2.2:8080` (ซ่อนใน release) |
| ShareSheet | เรียก `POST /runs/{uuid}/share` จริง · ลิงก์มาจาก server · ออฟไลน์ → ปุ่มขึ้น "ต้องเชื่อมต่อเน็ตเพื่อสร้างลิงก์" |
| WebPreviewActivity | เปิด URL จริง `https://<host>/s/{code}` ใน WebView (ไม่สร้าง HTML ในเครื่อง) · **ต้องเปิด JavaScript** เพราะแผนที่ใช้ Leaflet |
| RunDetail | ป้าย "เข้าอันดับแล้ว" / "ไม่เข้าอันดับ: เพิ่มเอง / ความเร็วผิดปกติ" |
| AddEditRun | การวิ่งที่เพิ่มเองไม่มีเส้นทาง → แสดง "ไม่มีเส้นทาง GPS" แทนเส้นจำลอง |
| Bottom nav | 5 แท็บ: หน้าหลัก · ประวัติ · อันดับ · สถิติ · ตั้งค่า |

### 3.6 รองรับทุกมือถือ (แนวตั้งเท่านั้น)
- ใส่ `android:screenOrientation="portrait"` ทุก Activity
  - ⚠️ ข้อจำกัด: ตั้งแต่ Android 16 (targetSdk 36+) จอใหญ่ ≥ 600dp (แท็บเล็ต/จอพับตอนกาง) **ระบบจะไม่สนค่านี้** — มือถือปกติยังล็อกแนวตั้งได้ จึงต้องให้ layout ไม่พังเมื่อจอกว้าง (จำกัดความกว้างเนื้อหา max ~600dp จัดกลาง)
- ทดสอบอย่างน้อย: จอเล็ก 360×640dp · Pixel 9 · จอยาว 20:9 · font scale 1.3 · มีรอยบาก (edge-to-edge มีอยู่แล้ว)
- ปุ่มสำคัญใน LiveRun ต้องไม่โดนแถบนำทางบัง

---

## 4. บั๊กที่พบตอนนี้ (ต้องแก้ในแผนนี้)

| # | ไฟล์ | ปัญหา | วิธีแก้ |
|---|---|---|---|
| 1 | `ui/widget/OsmRoute.kt` | `LIGHT = TileSourceFactory.MAPNIK` ดึง tile จาก tile.openstreetmap.org ทั้งที่ comment บอกว่าไม่ใช้ (OSM บล็อกแอปทั่วไปได้ → แผนที่ว่าง) | เปลี่ยนเป็น CARTO `voyager` / `light_all` เหมือนโหมดมืด |
| 2 | `ui/live/LiveRunActivity.kt` | ไม่มี foreground service → ปิดจอ/สลับแอป GPS หยุด หรือแอปถูก kill | `RunTrackingService` (ข้อ 3.3) |
| 3 | `LiveRunActivity.tick()` | นับเวลาด้วยการบวก `elapsedS++` ทุก 1 วิ → เวลาเพี้ยนเมื่อ Handler ช้า | ใช้ `elapsedRealtime()` ลบช่วงที่หยุด |
| 4 | `data/RunRepository.kt` | เก็บในหน่วยความจำ + โหลด `SampleData` ใหม่ทุกครั้ง → ปิดแอปข้อมูลหาย | SQLite (ข้อ 3.2) |
| 5 | `ui/home/MainActivity.kt` (กู้คืน → "บันทึกเท่าที่มี") | **ทิ้งจุด GPS ทั้งหมด** ไม่ได้เรียก `drafts.loadTrack()` · ใส่ cadence ปลอม `166` · ใช้ `routeSeed` สร้างเส้นจำลอง | ใช้ draft แถวเดียวกันใน SQLite ที่มีจุดครบ ปิดด้วย `end_time` |
| 6 | `LiveRunActivity` กล้อง | `TakePicturePreview()` ได้แค่รูป thumbnail ความละเอียดต่ำ | `TakePicture()` + `FileProvider` ได้รูปเต็ม แล้วย่อเหลือ 1280px |
| 7 | `LiveRunActivity.finishRun()` | `tempC ?: 29` · `Run.humidity = 68` ค่าปลอม | เป็น `null` เมื่อไม่มีเซนเซอร์ และซ่อนช่องนั้นใน RunStats |
| 8 | `data/PrefsHelper.kt` | ชื่อเริ่มต้นเป็นชื่อผู้พัฒนา ("ศุภณัฐ จันทร์เลข") น้ำหนัก 62 ฯลฯ → ผู้ใช้อื่นเห็นชื่อนี้ | ค่าเริ่มต้นว่าง ดึงจากบัญชี |
| 9 | `ui/share/ShareSheet.kt` | ซิงก์ปลอม (`postDelayed` 1.2 วิ) · สุ่มรหัสแชร์ในเครื่อง · `serverId = 100 + id` | เรียก API จริง |
| 10 | `net/ApiConfig.kt` | โดเมน `runtrail.app` ไม่ใช่ของเรา · path `/runtrail/` แบบ PHP | ใช้ `share_url` จาก server · base URL `/api/v1/` |
| 11 | Manifest | Android 9+ บล็อก HTTP (cleartext) → เรียก `http://10.0.2.2:8080` และอัปโหลดรูปไป MinIO `:9000` จะล้มเหลวเงียบ ๆ | ไฟล์ `src/debug/res/xml/network_security_config.xml` ใช้ `<base-config cleartextTrafficPermitted="true">` (config ระบุช่วง IP ไม่ได้ และ IP ของ Mac เปลี่ยนได้) · release ไม่มีไฟล์นี้ → HTTPS เท่านั้น |
| 12 | `AddEditRunActivity` | การวิ่งที่เพิ่มเองได้เส้นทางสุ่ม (`routeSeed = Random`) ดูเหมือนวิ่งจริง | `source = manual` ไม่มีเส้น ไม่เข้าอันดับ |
| 13 | `ui/share/WebPreviewActivity.kt` | สร้าง HTML + SVG เส้นทางจำลองในเครื่อง | เปิดหน้าเว็บจริงจาก server |
| 15 | `ui/share/WebPreviewActivity.kt` | `javaScriptEnabled = false` → หน้าเว็บจริงที่ใช้ Leaflet จะขึ้นว่าง | เปิด JS สำหรับ WebView นี้ และคง `shouldOverrideUrlLoading` ไว้ให้ลิงก์อื่นเปิดนอกแอป |
| 14 | `LocationTracker` | `getLastKnownLocation(GPS)` อย่างเดียว เปิดแอปในตึกจะไม่รู้ตำแหน่งเลย | ใช้ค่าล่าสุดของ GPS หรือ NETWORK อันที่ใหม่กว่า |

(ทุกข้อจะตรวจซ้ำกับโค้ดตอนลงมือ และเขียน unit test ให้ `domain/` ตามที่ README วางไว้)

---

## 5. ความปลอดภัย
- รหัสผ่าน argon2id · ไม่ log รหัสผ่าน/token · token ในแอปเข้ารหัสด้วย Keystore
- JWT secret ≥ 32 bytes มาจาก env / AWS SSM Parameter Store (ไม่ commit)
- ทุก query ตรวจ `user_id` เจ้าของ (กันดูการวิ่งคนอื่นด้วยการเดา UUID)
- Share code 8 ตัวจาก `crypto/rand` (32^8 ≈ 1 ล้านล้าน) เดายาก
- S3 bucket ปิด public ทั้งหมด (`BlockPublicAccess`) เข้าถึงผ่าน presigned เท่านั้น
- EC2 ใช้ IAM Role เข้าถึง S3 (ไม่มี access key ในเครื่อง)
- `.env`, `*.tfstate`, `*.tfvars` อยู่ใน `.gitignore`

---

## 6. ขึ้น AWS ด้วย Terraform

### 6.1 ตัวเลือกโครงสร้าง (เรียงจากถูกไปแพง)
| แบบ | ประกอบด้วย | ค่าใช้จ่ายโดยประมาณ* | เหมาะกับ |
|---|---|---|---|
| **A (แนะนำเริ่มต้น)** | EC2 `t4g.small` 1 ตัว รัน docker compose (api + postgres + Caddy) · EBS 20 GB · Elastic IP · S3 สำหรับรูป · backup DB รายวันขึ้น S3 | ~ $15–20/เดือน | โปรเจคเรียน / สาธิต |
| B | แบบ A แต่ DB ย้ายไป RDS PostgreSQL `db.t4g.micro` | ~ $30–40/เดือน | อยากได้ DB จัดการให้ + backup อัตโนมัติ |
| C | ECS Fargate + ALB + RDS + NAT Gateway | $80+/เดือน | production จริง ไม่จำเป็นตอนนี้ |

\* ราคาประมาณ ต้องเช็คหน้า AWS Pricing ของ region ที่เลือกอีกครั้ง · **กับดักค่าใช้จ่าย: NAT Gateway (~$32/เดือน) และ ALB (~$16/เดือน)** — แบบ A/B ไม่ใช้ทั้งสองอย่าง (ใช้ public subnet + security group แทน)
Region แนะนำ: `ap-southeast-1` (สิงคโปร์) ใกล้ไทย

### 6.2 HTTPS
- แอป release ต้องใช้ HTTPS → ต้องมี **โดเมน** (ซื้อถูก ๆ หรือใช้ซับโดเมนฟรีแบบ `<ip>.sslip.io` สำหรับเดโม)
- ใช้ **Caddy** เป็น reverse proxy ใน docker compose ขอใบรับรอง Let's Encrypt อัตโนมัติ (ไม่ต้องใช้ ALB/ACM)

### 6.3 โครงสร้าง Terraform (`infra/terraform/`)
```
infra/terraform/
├── bootstrap/          # สร้าง S3 bucket เก็บ tfstate ครั้งเดียว (ใช้ use_lockfile ของ S3 backend)
├── modules/
│   ├── network/        # VPC + 2 public subnet + IGW (ไม่มี NAT)
│   ├── storage/        # S3 รูป (private, encryption, CORS สำหรับ PUT, lifecycle ลบ upload ค้าง) + S3 backup
│   ├── compute/        # EC2 + SG (22 จาก IP ตัวเอง, 80/443) + EIP + IAM role (S3 + SSM) + user_data ติดตั้ง Docker
│   ├── database/       # (แบบ B) RDS + subnet group + SG รับจาก EC2 เท่านั้น
│   └── budget/         # AWS Budget แจ้งเตือนอีเมลเมื่อเกิน $20
├── envs/dev/main.tf    # เรียก modules · variables.tf · outputs.tf (ip, url, bucket)
└── README.md           # ขั้นตอน init / plan / apply / destroy
```
- Deploy โค้ด: GitHub Actions build image → push ขึ้น ECR (หรือ GHCR ฟรี) → SSH/SSM สั่ง `docker compose pull && up -d`
- คำสั่ง `terraform apply` **ผู้ใช้เป็นคนรันเอง** (ใช้บัญชีและเงินของผู้ใช้)

---

## 7. แผนลงมือเป็นเฟส (แต่ละเฟสมีเกณฑ์ผ่าน)

| เฟส | งาน | เกณฑ์ผ่าน |
|---|---|---|
| **1. แอปออฟไลน์ให้ใช้ได้จริงก่อน** | SQLite + `RunRepository` ใหม่ · `RunTrackingService` · แก้บั๊ก #1–8, #12, #14 · ล็อกแนวตั้ง | ป้อนเส้นทาง GPX ใน Emulator แล้ว**ปิดจอ 2 นาที** เส้นยังต่อเนื่อง · ปิดแอป (swipe kill) แล้วเปิดใหม่ การวิ่งยังอยู่ · โหมดเครื่องบินวิ่งได้ครบ |
| **2. Backend บน Docker** ✅ | โครง Go Fiber · migrations · auth (register/login/refresh/logout) · `/me` · health · test | `docker compose up` แล้ว `curl` สมัคร → ล็อกอิน → refresh → ใช้ refresh เก่าซ้ำโดนปฏิเสธ · `go test ./...` ผ่าน |
| **3. Login/Register ในแอป** | 2 หน้าใหม่ · `TokenStore` · OkHttp + Authenticator · Guest mode · network security config (debug) · ProfileSetup ↔ `/me` | สมัครจากแอปบน Emulator ได้ · ปิดเน็ตแล้วเปิดแอปยังเข้าได้ · token หมดอายุแล้ว refresh เอง |
| **4. ซิงก์การวิ่ง + รูป** (ฝั่ง server ✅) | `PUT/PATCH/DELETE/GET /runs` · server คำนวณใหม่/ตรวจโกง · presigned S3 · `SyncWorker` · ShareSheet ปุ่มซิงก์จริง | **วิ่งในโหมดเครื่องบิน → เปิดเน็ต → ขึ้น server ภายใน 1 นาที และมีแค่ 1 แถว** (กดซิงก์ซ้ำก็ไม่ซ้ำ) · รูปเปิดได้จาก MinIO · ลบในแอปแล้ว server ลบด้วย · ล็อกอินเครื่องที่ 2 เห็นประวัติครบ |
| **5. แชร์ + อันดับโลก** (ฝั่ง server ✅) | `/runs/{id}/share` · หน้าเว็บ `/s/{code}` (Leaflet) · ตัด 200 ม. ที่ server · leaderboard API · แท็บ "อันดับ" | เปิดลิงก์ในเบราว์เซอร์มือถือเห็นเส้นทางจริง · ดู JSON ของ `/public/runs/{code}` แล้ว**ไม่มีจุดในรัศมี 200 ม. จากจุดเริ่ม** · การวิ่งที่เพิ่มเองไม่ขึ้นในอันดับ · มี 3 บัญชีทดสอบอันดับเรียงถูก |
| **6. ขัดเกลา** | ดาวน์โหลดแผนที่ออฟไลน์ · แจ้งเตือนทุก 1 กม. · ทดสอบหลายขนาดจอ · unit test `domain/` · อัปเดต README | ผ่าน checklist จอเล็ก/ใหญ่/font 1.3 · test ผ่าน |
| **7. AWS** | Terraform แบบ A · Caddy HTTPS · CI deploy · backup DB | `terraform plan` สะอาด · (ผู้ใช้ apply) `https://<โดเมน>/readyz` = ok · แอป release ต่อได้ |

ลำดับนี้ตั้งใจให้ **แอปใช้งานออฟไลน์ได้สมบูรณ์ก่อน** (เฟส 1) แล้วค่อยต่อ server — ถ้า server ล่ม แอปยังใช้ได้เสมอ

---

## 8. คำถามที่ต้องตัดสินใจ (มีค่าเริ่มต้นให้แล้ว)
| เรื่อง | ค่าเริ่มต้นในแผนนี้ |
|---|---|
| ต้องล็อกอินก่อนใช้ไหม | ไม่บังคับ — มีโหมด Guest ใช้ออฟไลน์ได้ ซิงก์เมื่อล็อกอิน |
| ล็อกอินด้วย Google | ยังไม่ทำ (ต้องตั้ง Google Cloud project) — เพิ่มทีหลังได้ |
| ลืมรหัสผ่านทางอีเมล | ทำท้ายสุด (ต้องมีโดเมน + SES) |
| โครง AWS | แบบ A (EC2 ตัวเดียว + S3) |
| โดเมน | ใช้ `sslip.io` ระหว่างเดโม |

---

## 9. สิ่งที่เปลี่ยนจากแผนตอนลงมือทำ backend (10 พ.ย. 2026)

| เรื่อง | แผนเดิม | ที่ทำจริง | เหตุผล |
|---|---|---|---|
| S3 ในเครื่อง | MinIO + `minio-init` | **RustFS 1.0.4** และ API สร้าง bucket เองตอนเริ่ม | `minio/minio` บน Docker Hub/quay ดึงไม่ได้แล้ว · RustFS ใช้ S3 API เดียวกัน |
| Query | sqlc | pgx เขียน SQL ตรง ๆ | ไม่ต้องติดตั้ง/รัน code generator เพิ่ม โค้ดสั้นและอ่านตามได้ง่ายกว่าสำหรับโปรเจคขนาดนี้ |
| โครงโฟลเดอร์ | แยก package ต่อโดเมน | handler ทั้งหมดอยู่ `internal/api` · ตรรกะล้วนแยกเป็น `track`, `auth`, `storage`, `httpx` | handler ใช้ dependency ชุดเดียวกัน แยกมากไปจะต้องส่งต่อกันวุ่นวาย |
| Grace window ของ refresh | token เดิมได้ "คู่เดิม" กลับไป | token เดิมได้ **คู่ใหม่** ในชุดเดียวกัน | server เก็บแค่ hash ของ refresh token จึงส่งค่าเดิมซ้ำไม่ได้ ผลกับแอปเหมือนกัน |
| เปลี่ยนรหัสผ่าน | 204 | 200 + token คู่ใหม่ | เครื่องนี้ใช้งานต่อได้ทันที ส่วนเครื่องอื่นถูก logout |
| ตรวจช่วงเร็ว 30 วิ | > 9 ม./วิ | > **7 ม./วิ** | จุดที่กระโดดเกิน 9 ม./วิ ถูกตัดตั้งแต่ขั้นกรองแล้ว เกณฑ์ 9 จึงไม่มีวันทำงาน |
| Best 1/5/10K | — | ช่วงที่เร็วที่สุดจริงในเส้นทาง (sliding window) | หน้า PR ในแอปใช้การเฉลี่ยตามสัดส่วน ซึ่งโกงอันดับได้ · ค่า best ของ server จะ ≤ split ที่เร็วสุด |
| Endpoint เพิ่ม | — | `POST /me/avatar/confirm`, `DELETE /me/avatar`, `DELETE /runs/{id}/photo` | ขั้นตอนอัปโหลดรูปต้องมี confirm เหมือนรูปการวิ่ง |
| PUT การวิ่งที่ถูกลบแล้ว | — | 410 `run_deleted` | ให้แอปรู้ว่าถูกลบจากเครื่องอื่น |
| แก้เวลาเริ่มการวิ่ง GPS | — | ไม่อนุญาต (400 `gps_locked`) | กันการย้ายการวิ่งเข้าสัปดาห์อื่นในอันดับ · **AddEditRunActivity ต้องล็อกช่องวันเวลาสำหรับการวิ่ง GPS** |
| เส้นทางสาธารณะ | จุดทั้งหมดที่ตัดแล้ว | `segments` (แบ่งช่วงตรงที่ถูกซ่อน) + เพซต่อจุด ไม่มีเวลาของจุด | ไม่ลากเส้นผ่านบริเวณบ้าน และไม่เปิดเผยเวลาที่อยู่แต่ละจุด |
| ช่วงหยุดวิ่ง | — | `PUT /runs` รับ `pauses: [[start_ms, end_ms]]` และไม่นับระยะช่วงนั้น | แอปไม่นับระยะตอนหยุด (`filter.reset`) ถ้า server นับ การวิ่งจริงที่เดินระหว่างหยุดจะโดน `distance_mismatch` · **RunTrackingService ต้องบันทึกช่วงหยุดแล้วส่งมา** |
| `server_time` ของการซิงก์ | เวลาปัจจุบัน | ถอยหลัง 1 นาที | กันพลาดรายการที่บันทึกพร้อมกันแต่ commit ทีหลัง · แอปอาจได้รายการซ้ำ ให้รวมด้วย UUID |
| CORS | middleware cors | ไม่ใส่ | หน้าเว็บแชร์อยู่โดเมนเดียวกับ API และแอปมือถือไม่ใช้ CORS |

รายละเอียด API ฉบับที่ใช้จริงอยู่ที่ [`backend/README.md`](../backend/README.md)
