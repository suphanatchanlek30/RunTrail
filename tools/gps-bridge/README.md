# GPS bridge — ทดสอบ RunTrail ด้วย GPS จริงจาก iPhone

ไม่มีมือถือ Android? ใช้ iPhone เป็น "GPS" ให้ Android Emulator บนโน้ตบุ๊ก แล้วถือโน้ตบุ๊กเดิน/วิ่งได้เลย

```
iPhone (Safari ส่งตำแหน่งทุกวินาที) → Cloudflare Tunnel (HTTPS) → bridge.py บน Mac → adb emu geo fix → Emulator
```

## เริ่มใช้
```bash
# 1) เปิด Emulator + backend (cd backend && make up)
# 2) รัน bridge (จะพิมพ์รหัส k ให้)
python3 tools/gps-bridge/bridge.py
# 3) อีกหน้าต่าง: เปิดลิงก์ HTTPS ชั่วคราว (Safari ให้ตำแหน่งเฉพาะเว็บ HTTPS)
cloudflared tunnel --url http://localhost:8765
# 4) บน iPhone เปิด  https://<ชื่อ>.trycloudflare.com/?k=<รหัส>  แล้วกด "เริ่มส่งตำแหน่ง"
# 5) กันโน้ตบุ๊กหลับ
caffeinate -dimsu
```

## ข้อควรรู้
- โน้ตบุ๊กต้องมีเน็ตตลอดทาง → ต่อ **Personal Hotspot ของ iPhone**
- เปิดหน้าเว็บค้างไว้ อย่าล็อกจอ iPhone หรือสลับแอป (Safari หยุดส่งตำแหน่งเมื่ออยู่เบื้องหลัง)
- อย่าปิดฝาโน้ตบุ๊ก
- แอปใน Emulator ต่อ backend ผ่าน `10.0.2.2` ซึ่งไม่ขึ้นกับเน็ตที่ใช้ แต่ **อัปโหลดรูป/ลิงก์แชร์** ใช้ IP ของ Mac ใน `backend/.env` ถ้าเปลี่ยนเน็ตแล้ว IP เปลี่ยน ให้แก้ `.env` แล้ว `make up` ใหม่หลังกลับมา
- รหัส `k` กันคนอื่นที่รู้ลิงก์มาสั่งตำแหน่ง · ลิงก์ trycloudflare เปลี่ยนทุกครั้งที่รัน cloudflared ใหม่
