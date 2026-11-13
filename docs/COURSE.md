# การนำเนื้อหา MA08–MA13 มาใช้

[← กลับไปหน้า README](../README.md)

บทเรียนแต่ละบทถูกใช้ตรงไหนในโปรเจค และจุดที่ตั้งใจทำต่างจากตัวอย่างในสไลด์

| บทเรียน | ใช้ในโปรเจค |
|---|---|
| **MA08** Input Controls | `EditText` ทุกฟอร์ม · `RadioGroup` + `RadioButton` ใน sheet เลือกค่า (`Sheets.choose`: หน่วย ธีม ภาษา) · `CheckBox` เลือกวันเตือนไปวิ่ง และ "ลบจากเซิร์ฟเวอร์ด้วย" · `SwitchCompat` เปิด/ปิดการเตือน · `Spinner` เรียงลำดับในหน้าประวัติ (layout 2 ตัว) · `AutoCompleteTextView` เลือกประเทศ (`setThreshold(1)`) · `RatingBar` ความรู้สึกหลังวิ่ง · `DatePickerDialog` / `TimePickerDialog` |
| **MA09** ข้อมูลภายในเครื่อง + คืนสถานะ | `SharedPreferences` (โปรไฟล์ ตั้งค่า เวลาเตือน) · App-specific Storage: `openFileOutput` + `Properties` (`BackupInfo` บันทึกการส่งออก/นำเข้าล่าสุด) · SAF: `CreateDocument` ส่งออก / `OpenDocument` นำเข้าประวัติเป็นไฟล์ JSON · `onSaveInstanceState` ทุกหน้าที่มีสถานะ |
| **MA10** SQLite + RecyclerView + รูปภาพ | `RunDb` (`SQLiteOpenHelper` v2: runs · run_points · splits) · `RunRepository` CRUD/ค้นหา/กรอง/เรียง · RecyclerView ในประวัติ อันดับ การแจ้งเตือน · กล้อง/แกลเลอรี + EXIF · แก้ไขแล้ว `setResult(RESULT_OK)` กลับหน้ารายละเอียด |
| **MA11** ฐานข้อมูลออนไลน์ | แอปไม่ต่อฐานข้อมูลตรง เรียกผ่าน REST API (Go + PostgreSQL) แล้วรับ JSON · **Volley** สำหรับคำขอจากหน้าจอ: `ApiConfig` (รวม URL) · `VolleyApi.getQueue()` · `JsonObjectRequest` 5 พารามิเตอร์ (อันดับ สรุปนักวิ่ง ประเทศ การแจ้งเตือน เปลี่ยนรหัสผ่าน แชร์/ยกเลิกแชร์) · **OkHttp** สำหรับซิงก์ใน WorkManager (ต้องเรียกแบบรอผล) |
| **MA12** เซนเซอร์ | GPS (`LocationTracker` + foreground service) · Step Counter (Cadence) · เข็มทิศ · หน้าตรวจเซนเซอร์ · register ใน `onResume` / unregister ใน `onPause` |
| **MA13** Scheduler + Notification | เตือนไปวิ่ง: `ReminderScheduler` → `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` → `ReminderReceiver` แสดงแจ้งเตือนแล้วตั้งรอบถัดไป · `BootReceiver` (BOOT_COMPLETED) ตั้งเวลากลับหลังรีบูต · ตรวจ `canScheduleExactAlarms()` · ขอ `POST_NOTIFICATIONS` ตอนรัน |

**ต่างจากตัวอย่างในสไลด์ (ตั้งใจ)**
- เขียนด้วย Kotlin แทน Java (API ของ Android ชุดเดียวกัน)
- backend เป็น Go + PostgreSQL แทน PHP + MySQL แต่หลักการเดียวกัน: แอป → HTTP → สคริปต์ฝั่งเซิร์ฟเวอร์ → ฐานข้อมูล → JSON กลับมา
- รูปเก็บเป็นไฟล์ (SQLite เก็บแค่ path) และอัปโหลดตรงไป S3 แทน Base64 ใน JSON: แถวที่ใหญ่เกิน ~2 MB อ่านจาก SQLite ไม่ได้ และ Base64 ทำให้ข้อมูลใหญ่ขึ้นราว 33%
- อนุญาต HTTP ด้วย `network_security_config` เฉพาะ build debug แทน `usesCleartextTraffic="true"` (release ใช้ HTTPS เท่านั้น)
- `ApiConfig.BASE_URL` อ่านค่าตอนรัน ไม่ใช่ค่าคงที่ เพราะผู้ใช้เปลี่ยนที่อยู่เซิร์ฟเวอร์ได้
