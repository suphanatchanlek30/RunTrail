# ความเป็นส่วนตัว ความปลอดภัย และสิทธิ์ของแอป

[← กลับไปหน้า README](../README.md)

ข้อมูลผู้ใช้ถูกปกป้องอย่างไร และแอปขอสิทธิ์อะไรบ้างเพราะอะไร

- [ความเป็นส่วนตัวและความปลอดภัย](#ความเป็นส่วนตัวและความปลอดภัย)
- [สิทธิ์ที่แอปขอ](#สิทธิ์ที่แอปขอ)

---

## ความเป็นส่วนตัวและความปลอดภัย

- รหัสผ่านเข้ารหัสด้วย **argon2id** · access token อายุ 15 นาที · refresh token หมุนทุกครั้ง ใช้ซ้ำ = ยกเลิกทั้งชุด
- token ในเครื่องเข้ารหัส **AES-GCM ด้วย Android Keystore** และไม่ถูกสำรองไปเครื่องอื่น
- ทุก query ตรวจเจ้าของข้อมูล · ส่งการวิ่งด้วย UUID ของคนอื่นถูกปฏิเสธ (409)
- **ซ่อน 200 ม. รอบจุดเริ่ม/จบที่เซิร์ฟเวอร์** ก่อนส่งให้คนอื่น และไม่เปิดเผยเวลาของแต่ละจุด
- S3 bucket ปิดสาธารณะ เข้าถึงผ่าน presigned URL อายุสั้นเท่านั้น
- release build ใช้ HTTPS เท่านั้น (HTTP อนุญาตเฉพาะ build debug)
- ไฟล์ลับ (`.env`, key, keystore, state ของ Terraform) อยู่ใน `.gitignore`

---

## สิทธิ์ที่แอปขอ

| สิทธิ์ | เหตุผล |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | บันทึกเส้นทางและคำนวณระยะทาง (จำเป็น) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | บันทึก GPS ต่อเนื่องขณะปิดจอ |
| `ACTIVITY_RECOGNITION` | อ่าน Step Counter เพื่อคำนวณ Cadence |
| `POST_NOTIFICATIONS` | สถานะการวิ่ง · เพซทุก 1 กม. · สถิติใหม่และอันดับ |
| `INTERNET` / `ACCESS_NETWORK_STATE` | แผนที่ OpenStreetMap และซิงก์กับ backend |
| `SCHEDULE_EXACT_ALARM` | เตือนไปวิ่งตรงเวลา (Android 12+ ผู้ใช้ต้องอนุญาตเอง ถ้าไม่อนุญาตจะเตือนแบบไม่แม่นยำแทน) |
| `RECEIVE_BOOT_COMPLETED` | ตั้งเวลาเตือนไปวิ่งกลับหลังเปิดเครื่องใหม่ |
| `WRITE_EXTERNAL_STORAGE` (Android 9 เท่านั้น) | บันทึกภาพแชร์ลงแกลเลอรี |
| `ACCESS_LOCAL_NETWORK` (debug เท่านั้น) | Android 17: ต่อ backend ที่รันในวง Wi-Fi / Emulator |

เซนเซอร์ทุกตัวประกาศ `required="false"` เครื่องที่ไม่มีบางตัวยังติดตั้งและใช้งานได้
