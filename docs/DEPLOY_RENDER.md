# Deploy ฟรี: Render + Neon + Cloudflare R2

คู่มือขึ้นระบบจริงแบบไม่เสียเงิน สำหรับผู้ใช้จำนวนน้อย (ออกแบบสำหรับ 1 คน) ทำตามทีละขั้นได้ทันที

| ส่วน | ใช้ | แทนของเดิมในเครื่อง |
|---|---|---|
| Backend (Go, Docker) | **Render** — Web Service แพ็กเกจ Free | container `api` |
| ฐานข้อมูล | **Neon** — PostgreSQL แพ็กเกจ Free | container `db` (Postgres 17) |
| ที่เก็บรูป | **Cloudflare R2** — แพ็กเกจ Free | container `s3` (RustFS) |

**สิ่งที่เตรียมไว้ในโปรเจคแล้ว** (ไม่ต้องแก้โค้ดก่อน deploy):

| ไฟล์ | ทำอะไร |
|---|---|
| [`render.yaml`](../render.yaml) | Blueprint ของ Render: Docker, Free, Singapore, health check, ตัวแปรทั้งหมด, สุ่ม `JWT_SECRET` ให้เอง |
| `backend` `S3_CREATE_BUCKET` | ปิดการสร้าง bucket ตอนเริ่ม (bucket ของ R2 สร้างในหน้าเว็บ) |
| `backend` `/readyz` | ตรวจที่เก็บรูปด้วยสิทธิ์ระดับไฟล์ — ใช้ได้กับ token R2 แบบ "Object Read & Write" |
| `backend/Makefile` `deploy-check` | ปลุกเซิร์ฟเวอร์ที่หลับ แล้วรัน smoke test 29 ข้อกับเซิร์ฟเวอร์จริง |
| แอป `ApiClient` | รอคำตอบได้ 90 วินาที (เดิม 30) — เซิร์ฟเวอร์ที่เพิ่งตื่นไม่ทำให้ล้ม |
| แอป `App` / หน้าเข้าสู่ระบบ | ปลุกเซิร์ฟเวอร์ล่วงหน้าตอนเปิดแอปและตอนเปิดหน้าเข้าสู่ระบบ |
| แอป หน้าเข้าสู่ระบบ/สมัคร | รอเกิน 6 วินาที → แจ้ง "เซิร์ฟเวอร์กำลังตื่น อาจใช้เวลาถึง 1 นาที" |
| `app/build.gradle.kts` | release build ชี้ `https://runtrail-api.onrender.com` (เปลี่ยนได้ด้วย `-PreleaseApiUrl=`) · debug ยังชี้ emulator |

ทั้งหมดซ้อมในเครื่องแล้วด้วยค่าตั้งแบบเดียวกับ Render (image ที่ build จาก `./backend`, `PORT=10000`, ฐานข้อมูลว่างใหม่,
`S3_REGION=auto`, `S3_CREATE_BUCKET=false`) → `make deploy-check` ผ่าน 29/29
**ยังไม่เคย deploy ขึ้น Render/Neon/R2 จริง** — จุดที่ยืนยันกับของจริงไม่ได้มีป้าย ⚠️

---

## 0. ข้อจำกัดของแพ็กเกจฟรี

| เจ้า | ข้อจำกัด | ผลกับแอป |
|---|---|---|
| Render | หลับหลังไม่มีคนเรียก 15 นาที ปลุกใช้ ~1 นาที · RAM 512 MB · CPU 0.1 · 750 ชม./เดือน | แอปรอได้และแจ้งผู้ใช้แล้ว · ลิงก์แชร์ที่เพื่อนเปิดครั้งแรกจะช้า |
| Render | ไม่มีดิสก์ถาวร · บางแหล่งรายงานว่า bandwidth ฟรีเหลือ 5 GB/เดือน | ไม่กระทบ — รูปโหลดตรงจาก R2 ไม่ผ่าน Render |
| Neon | 1 GB/โปรเจค · compute 100 CU-ชม./เดือน · พักเองเมื่อว่าง | เกินพอสำหรับ 1 คน |
| R2 | 10 GB/เดือน · ดาวน์โหลดฟรี | รูปละ ≤ 1280 px (~200 KB) → หลายหมื่นรูป |

**ห้ามใช้ Postgres ฟรีของ Render** — ถูกลบพร้อมข้อมูลหลัง 30 วัน (+14 วันผ่อนผัน) และไม่มี backup

---

## 1. Neon — ฐานข้อมูล

1. สมัครที่ <https://neon.com> → **Create project**
   - ชื่อ `runtrail` · Postgres **17** · Region **AWS Asia Pacific (Singapore)**
2. **Connection details** → database `neondb` → **ปิด Connection pooling** → คัดลอก connection string แบบ direct (host ไม่มี `-pooler`)
   ⚠️ แบบ pooled ยังไม่ได้ทดสอบ (backend รัน migration ด้วย goose และมี pool ของ pgx อยู่แล้ว)
3. ตรวจว่าท้าย URL มี `sslmode=require`:
   ```
   postgresql://neondb_owner:xxxx@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
   ```
   → ค่า **`DATABASE_URL`** · ตารางสร้างเองตอน backend เริ่ม (migration ฝังอยู่ใน binary)

## 2. Cloudflare R2 — ที่เก็บรูป

1. Cloudflare → **R2 Object Storage** → เปิดใช้ (ผูกบัตรเพื่อยืนยัน ไม่ถูกเก็บเงินถ้าไม่เกินโควตาฟรี)
2. **Create bucket** ชื่อ **`runtrail-photos`** · ปล่อยเป็น **private** (แอปใช้ลิงก์ presigned อายุสั้น ไม่ต้องเปิด public)
3. **Manage R2 API Tokens → Create API token** → Permissions **Object Read & Write** → Specify bucket `runtrail-photos`
   → คัดลอก **Access Key ID** และ **Secret Access Key** (Secret เห็นได้ครั้งเดียว)
4. คัดลอก **Account ID** → endpoint คือ `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`

## 3. Render — backend (ใช้ Blueprint)

1. สมัคร <https://render.com> ด้วย GitHub และให้สิทธิ์ repo `RunTrail`
2. **New → Blueprint** → เลือก repo → Render อ่าน [`render.yaml`](../render.yaml)
3. Render ถามค่าความลับ 4 ตัว:

   | Key | ค่า |
   |---|---|
   | `DATABASE_URL` | จากขั้นที่ 1 |
   | `S3_ENDPOINT` | `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` |
   | `S3_ACCESS_KEY` | Access Key ID ของ R2 |
   | `S3_SECRET_KEY` | Secret Access Key ของ R2 |

   ค่าที่เหลือตั้งไว้ใน `render.yaml` แล้ว: `PUBLIC_BASE_URL`, `S3_REGION=auto`, `S3_BUCKET=runtrail-photos`, `S3_PATH_STYLE=true`,
   `S3_CREATE_BUCKET=false`, `LOG_LEVEL=info` และ `JWT_SECRET` (Render สุ่มให้ 256 บิต) · `PORT` Render ใส่ให้เอง
4. **Apply** → รอ build (หลายนาทีครั้งแรก) → Logs ต้องเห็น `"database ready"` และ `"listening"`
5. ⚠️ **ถ้าได้ URL ไม่ใช่ `runtrail-api.onrender.com`** (ชื่อซ้ำกับคนอื่น) → แก้ `PUBLIC_BASE_URL` ในหน้า Environment ให้ตรง URL จริง
   และ build แอปด้วย `-PreleaseApiUrl=<URL จริง>` (ขั้นที่ 5)

**หมายเหตุ Blueprint:** ค่า `sync: false` ถูกถามแค่ตอนสร้างครั้งแรก ถ้าภายหลังเพิ่ม secret ใหม่ใน `render.yaml` ต้องไปใส่ในหน้า Environment เอง
· deploy ใหม่อัตโนมัติเมื่อ push เข้า `main` และมีไฟล์ใน `backend/` เปลี่ยน

<details>
<summary>ทางเลือก: สร้าง Web Service เองโดยไม่ใช้ Blueprint</summary>

New → Web Service → repo นี้ → Language **Docker** · Dockerfile path `./backend/Dockerfile` · Docker build context `./backend` ·
Instance **Free** · Region **Singapore** · Health Check Path `/healthz` · แล้วใส่ตัวแปรทั้ง 11 ตัวตาม `render.yaml`
(`JWT_SECRET` สร้างเองด้วย `openssl rand -hex 32`)
</details>

## 4. ตรวจหลัง deploy

```bash
cd backend
make deploy-check URL=https://runtrail-api.onrender.com
# ปลุกเซิร์ฟเวอร์ (รอได้ 2 นาที) → {"db":"ok","storage":"ok"} → smoke test → ALL 29 CHECKS PASSED
```

smoke test สร้างบัญชีทดสอบ `smoke…@example.com` ทิ้งไว้ (การวิ่งของบัญชีนั้นถูกลบตอนจบ)

| ถ้า… | ตรวจ |
|---|---|
| `readyz` บอก db ไม่ ok | `DATABASE_URL`, `sslmode=require`, ใช้ direct ไม่ใช่ pooler |
| `readyz` บอก storage ไม่ ok | `S3_ENDPOINT`, key, ชื่อ bucket, token มีสิทธิ์กับ bucket นี้ |
| อัปโหลดรูปได้ 403 `SignatureDoesNotMatch` | `S3_REGION=auto`, `S3_PATH_STYLE=true` |
| ลิงก์แชร์ชี้ผิดที่ | `PUBLIC_BASE_URL` ต้องตรง URL ของ Render |

## 5. ตั้งค่าแอป

- **build release** (ชี้ Render ให้แล้ว): `./gradlew assembleRelease` — ถ้า URL ไม่ตรง: `./gradlew assembleRelease -PreleaseApiUrl=https://<url>`
  (การติดตั้ง release ต้อง sign APK เอง — ยังไม่ได้ตั้ง signing config ในโปรเจค)
- **ใช้ debug build ก็ได้:** หน้าเข้าสู่ระบบ → **เซิร์ฟเวอร์** → `https://runtrail-api.onrender.com`
- สมัครบัญชีใหม่บนเซิร์ฟเวอร์นี้ (คนละฐานข้อมูลกับใน Mac)
- ถ้าแอปล็อกอินกับเซิร์ฟเวอร์ใน Mac อยู่ แอปจะไม่ยอมย้ายไปเซิร์ฟเวอร์อื่นจนกว่าจะออกจากระบบ (กันข้อมูลซิงก์ผิดที่):
  ซิงก์ให้ครบ → ออกจากระบบ → เปลี่ยนเซิร์ฟเวอร์ → สมัคร/ล็อกอิน · การวิ่งตอนเป็น Guest จะถูกผูกบัญชีและอัปโหลดให้เอง

## 6. ดูแลระบบ

- **สำรองข้อมูล** (แพ็กเกจฟรีไม่ควรถือเป็นที่เก็บถาวร):
  ```bash
  pg_dump "<DATABASE_URL>" --format=custom --file=runtrail_$(date +%F).dump
  pg_restore --clean --if-exists -d "<DATABASE_URL>" runtrail_YYYY-MM-DD.dump   # คืนข้อมูล
  ```
  รูปใน R2 ไม่ได้อยู่ใน dump
- **อัปเดต:** push เข้า `main` → Render deploy ใหม่เอง · migration ใหม่รันตอนเริ่ม
- **Log:** Render Dashboard → `runtrail-api` → Logs (JSON)
- **งานลบรูปที่อัปโหลดค้าง** รันทุกชั่วโมงเฉพาะตอนเซิร์ฟเวอร์ตื่น — บนแพ็กเกจฟรีจึงช้ากว่าปกติ ไม่กระทบการใช้งาน
- **เปลี่ยนความลับ:** เปลี่ยน `JWT_SECRET` = ทุกเครื่องต้องล็อกอินใหม่ · หมุน key R2 = แก้ `S3_ACCESS_KEY/S3_SECRET_KEY`
- `PROXY_HEADER` ปล่อยว่าง: rate limit นับทุกคำขอที่ผ่าน proxy ของ Render รวมกัน (พอสำหรับ 1 คน)
  ⚠️ ถ้าจะตั้ง `X-Forwarded-For` ต้องทดสอบก่อนว่า IP ที่อ่านได้ถูกต้อง

## 7. Checklist

- [ ] Neon: โปรเจค Singapore · Postgres 17 · direct URL + `sslmode=require`
- [ ] R2: bucket `runtrail-photos` (private) · token Object Read & Write · Account ID
- [ ] Render: New → Blueprint → ใส่ 4 ค่า → Apply → Logs มี `listening`
- [ ] URL ตรง `runtrail-api.onrender.com` (ถ้าไม่ตรง แก้ `PUBLIC_BASE_URL`)
- [ ] `make deploy-check URL=https://runtrail-api.onrender.com` → 29/29
- [ ] แอป: ตั้งเซิร์ฟเวอร์ → สมัคร → วิ่ง → ซิงก์ → แชร์ลิงก์ → เปิดลิงก์ในเบราว์เซอร์
- [ ] ตั้งเตือนตัวเองให้ `pg_dump` เป็นระยะ

## อ้างอิง

- Render: [Free instances](https://render.com/docs/free) · [Blueprint YAML reference](https://render.com/docs/blueprint-spec) · [Monorepo support](https://render.com/docs/monorepo-support)
- Neon: [Free plan limits](https://neon.com/faqs/free-plan-limits-and-quotas) · [Pricing](https://neon.com/pricing)
- Cloudflare R2: [Docs](https://developers.cloudflare.com/r2/) · [Pricing](https://developers.cloudflare.com/r2/pricing/)
- สรุปข้อจำกัดจากบุคคลที่สาม: [agentdeals.dev/vendor/render](https://agentdeals.dev/vendor/render), [temps.sh](https://temps.sh/blog/render-pricing-2026)
