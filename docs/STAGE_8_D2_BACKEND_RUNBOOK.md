# دليل تشغيل واختبار طبقة الخادم محلياً — المرحلة الثامنة (D2)

هذا الدليل يوضح كيفية تشغيل الخادم المحلي (`Node.js + TypeScript + Express`) واختباره باستخدام **Firebase Emulator** و**Mock Cloudflare R2** دون إنشاء موارد سحابية حقيقية أو نشر قواعد إنتاجية.

---

## 1. المتطلبات المسبقة محلياً

- **Node.js `22+`** و`npm`
- **Java JDK `21+`** (مطلوب لتشغيل محاكي Firestore في `firebase-tools`)
- اعتماديات المشروع مثبتة عبر `npm install`

---

## 2. أوامر التشغيل والاختبار المحلية

### أ) تشغيل اختبارات الخادم الكاملة على محاكي Firestore + Mock R2
```bash
npm run test:backend
```
يشغّل هذا الأمر محاكي Firestore المحلي تلقائياً ويُنفّذ حزمة `tests/backend/backend.api.test.ts` للتحقق من:
- المصادقة (`verifyIdToken(token, true)` + إبطال الرموز + `admin`/`owner`/`student`/`guest`/مجهول).
- نقاط قراءة الفهرس الحية (`/api/catalog/*`) والتحقق الحي من 5 مستويات دون كاش داخلي.
- إنشاء مسودات الإصدارات وحظر الحقول الحساسة من العميل.
- دورة رفع PDF الكاملة (`upload-slot` -> `PUT` على Staging -> `verify` -> `publish`).
- التزامن وفقدان القفل (`Fencing Token`) وانتهاء المهلة وإعادة المحاولة (`Idempotent Retry`) وحماية الكائن النهائي عند غموض Firestore.
- مساري `/stream` و`/download` مع منع تنزيل الضيوف والمستخدمين المجهولين.

### ب) تشغيل اختبارات قواعد Firestore (D1) وفحص الأنواع والبناء
```bash
npm run test:rules
npm run lint
npm run build
```

### ج) تشغيل خادم الـ API محلياً مع محاكي Firestore (للتجربة التفاعلية)
1. شغّل محاكي Firestore في نافذة مستقلة:
   ```bash
   npx firebase emulators:start --only firestore --project student-learning-platform-2026
   ```
2. شغّل الخادم:
   ```bash
   FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_PROJECT_ID=student-learning-platform-2026 PORT=8081 npm run dev:api
   ```
3. افحص الجاهزية:
   ```bash
   curl http://127.0.0.1:8081/api/health
   ```
   *(يعمل الخادم بوضع Fail-Closed لـ R2 إلى أن تُضبط مفاتيح R2 الحقيقية في متغيرات البيئة في المراحل اللاحقة).*

---

## 3. المتغيرات البيئية والسرية المطلوبة لاحقاً (Cloud Run + Cloudflare R2)

**تحذير أمني:** لا تضع أي أسرار حقيقية داخل المستودع أو داخل متغيرات `VITE_*`. تُحقن القيم الحساسة حصراً عبر بيئة الخادم (Secret Manager / Cloud Run Environment Variables):

| المتغير | الوصف | سري؟ |
| :--- | :--- | :--- |
| `PORT` | منفذ الاستماع للخادم (افتراضياً `3000`، أو `8080` في Cloud Run) | لا |
| `FIREBASE_PROJECT_ID` | معرّف مشروع Firebase (`student-learning-platform-2026`) | لا |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | JSON حساب الخدمة لـ Firebase Admin SDK (أو يُترك فارغاً عند الاعتماد على ADC في Cloud Run) | **نعم** |
| `FIRESTORE_EMULATOR_HOST` | يُستخدم للتطوير/الاختبار المحلي فقط (`127.0.0.1:8080`) | لا |
| `R2_ACCOUNT_ID` | معرّف حساب Cloudflare (أو يستخدم مع `R2_ENDPOINT`) | نعم/تهيئة |
| `R2_ENDPOINT` | نقطة اتصال S3-compatible لـ Cloudflare R2 (`https://<account>.r2.cloudflarestorage.com`) | لا |
| `R2_BUCKET_NAME` | اسم حاوية R2 المخصصة للملفات الدراسية | لا |
| `R2_ACCESS_KEY_ID` | مفتاح الوصول لـ Cloudflare R2 API Token (أقل صلاحيات على Bucket واحد فقط) | **نعم** |
| `R2_SECRET_ACCESS_KEY` | المفتاح السري لـ Cloudflare R2 API Token | **نعم** |
| `ALLOWED_ORIGINS` | قائمة المصادر المسموحة لـ CORS مفصولة بفاصلة (بدون `*`) | لا |
| `UPLOAD_SESSION_TTL_SECONDS` | مدة صلاحية جلسة الرفع المؤقتة بالثواني (افتراضي `600` = 10 دقائق) | لا |
| `VERIFY_LOCK_TTL_SECONDS` | مدة قفل محاولة التحقق بالثواني (افتراضي `300` = 5 دقائق) | لا |
| `MAX_PDF_BYTES` | الحد الأقصى المسموح لحجم ملف PDF بالبايت (افتراضي `104857600` = 100 MiB) | لا |
| `HAQIBATI_BACKEND_BASE_URL` | عنوان الـ HTTPS للخادم الوسيط يُمرّر لبناء Android عبر Gradle property أو متغير بيئة (`BuildConfig`) دون كشف أي أسرار R2 | لا |

---

## 4. ربط تطبيق Android بـ Backend D2

لتفعيل جلب الفهرس الحي ووسيط R2 والبث والتنزيل في تطبيق Android دون إدخال أي مفاتيح أو روابط داخل الكود المصدري:
```bash
cd android
HAQIBATI_BACKEND_BASE_URL="https://api.example.com" ./gradlew testDebugUnitTest assembleDebug
```
أو عبر Gradle property:
```bash
cd android
./gradlew assembleDebug -PHAQIBATI_BACKEND_BASE_URL="https://api.example.com"
```
عند ترك `HAQIBATI_BACKEND_BASE_URL` فارغاً، يعمل التطبيق بوضع **Fail-Closed** الآمن (`AWAITING_SECURE_BROKER_INTEGRATION`) دون استخدام روابط غير مشفرة (`http://`) أو أي بيانات وهمية.
