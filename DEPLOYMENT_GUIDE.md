# دليل تشغيل ونشر خادم FOUL REELS (Backend Deployment Guide)

هذا الدليل يوضح خطوات تجهيز ونشر خادم **FOUL REELS** الجديد (`app.py`) وربطه بقاعدة بيانات **Firebase Realtime Database** الجديدة عبر **Firebase Admin SDK** ونظام تخزين **Telegram Storage**.

---

## 1. المتغيرات المطلوبة في لوحة الاستضافة (Environment Variables)

أضف المتغيرات التالية في إعدادات البيئة (Environment Variables) داخل لوحة الاستضافة:

| المتغير | الوصف | إلزامي؟ |
|---|---|---|
| `FIREBASE_DATABASE_URL` | رابط قاعدة بيانات Firebase Realtime Database الجديدة (مثال: `https://<PROJECT_ID>-default-rtdb.firebaseio.com`) | **نعم** |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | محتوى ملف حساب الخدمة (Service Account JSON) كاملاً كنص واحد (أو استخدم `FIREBASE_SERVICE_ACCOUNT_FILE` لتحديد مسار الملف) | **نعم** |
| `TELEGRAM_API_ID` | رقم `api_id` الخاص بتطبيق تيليجرام من `https://my.telegram.org` | **نعم** |
| `TELEGRAM_API_HASH` | كود `api_hash` الخاص بتطبيق تيليجرام من `https://my.telegram.org` | **نعم** |
| `TELEGRAM_SESSION` أو `TELEGRAM_BOT_TOKEN` | كود الجلسة (`StringSession`) أو توكن البوت المشرف في قناة التخزين | **نعم** (أحدهما) |
| `TELEGRAM_CHANNEL_ID` | معرف قناة تيليجرام المخصصة لتخزين مقاطع الريلز (مثل `@channel_username` أو `-100123456789`) | **نعم** |
| `HOST` | عنوان الاستماع (اتركه `0.0.0.0`) | نعم |
| `PORT` | المنفذ الذي توفره الاستضافة تلقائياً | نعم |
| `SERVER_URL` | الرابط العام النهائي للخادم بصيغة `https://...` (يُضاف بعد حصولك على رابط الاستضافة) | اختياري مبدئياً |

---

## 2. خطوات التثبيت والتشغيل على الاستضافة

### تثبيت المكتبات:
```bash
pip install -r requirements.txt
```

### أمر التشغيل (Start Command):
```bash
python app.py
```
أو مباشرة عبر Uvicorn:
```bash
uvicorn app:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1
```

---

## 3. فحص نجاح الاتصال (Health Check)

بعد تشغيل الخادم، افتح الرابط التالي في المتصفح:
```text
https://<YOUR_SERVER_DOMAIN>/health
```
يجب أن تحصل على استجابة JSON تؤكد الاتصال الفعلي بقاعدة البيانات الجديدة وتيليجرام:
```json
{
  "ok": true,
  "status": "online",
  "firebase_connected": true,
  "database_mode": "admin",
  "local_fallback_enabled": false,
  "telegram_connected": true,
  "ready_for_database": true,
  "ready_for_media": true
}
```

---

## 4. مسارات API المحمية بـ Firebase ID Token

جميع عمليات الرفع والحذف والتعديل والتفاعل تتطلب إرسال ترويسة:
```http
Authorization: Bearer <FIREBASE_ID_TOKEN>
```
ويقوم الخادم بالتحقق من التوكن عبر `firebase_admin.auth.verify_id_token` واستخراج `uid` الموثق منه مباشرةً دون الوثوق بأي `uid` خارجي.
