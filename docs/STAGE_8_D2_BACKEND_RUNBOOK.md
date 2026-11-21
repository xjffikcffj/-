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
| `TRUST_PROXY` | عدد قفزات البروكسي الموثوقة في Express (`false` افتراضياً محلياً، أو `1` خلف Google Front End في Cloud Run؛ يُمنع التفعيل العام `true`) | لا |
| `UPLOAD_SESSION_TTL_SECONDS` | مدة صلاحية جلسة الرفع المؤقتة بالثواني (افتراضي `600` = 10 دقائق) | لا |
| `VERIFY_LOCK_TTL_SECONDS` | مدة قفل محاولة التحقق بالثواني (افتراضي `300` = 5 دقائق) | لا |
| `MAX_PDF_BYTES` | الحد الأقصى المسموح لحجم ملف PDF بالبايت (افتراضي `104857600` = 100 MiB) | لا |
| `HAQIBATI_BACKEND_BASE_URL` | عنوان الـ HTTPS للخادم الوسيط يُمرّر لبناء Android عبر Gradle property أو متغير بيئة (`BuildConfig`) دون كشف أي أسرار R2 | لا |

### حدود Rate Limiting قبل النشر على Cloud Run
- يطبّق الخادم حالياً حماية **محلية في الذاكرة لكل نسخة (`InMemoryRateLimiter`)** كطبقة دفاع إضافية فقط، و**ليست** حماية موزعة عبر عدة نسخ أو بديلاً عن حماية Edge.
- عند النشر على Cloud Run (حيث تتعدد الحاويات تلقائياً وتُعاد تهيئة الذاكرة عند scale-to-zero)، يلزم قبل الإنتاج تفعيل **Edge / Load Balancer Rate Limiting** (مثل **Google Cloud Armor** على External HTTPS Load Balancer أو **Cloudflare WAF Rate Limiting**) أمام Cloud Run، مع ضبط `TRUST_PROXY=1` (أو عدد القفزات الموثوقة الدقيق فقط) وعدم الوثوق برؤوس `X-Forwarded-For` القادمة من العملاء مباشرة.

### سياسة Cloudflare R2 Lifecycle المقترحة لمسار Staging فقط (مؤجلة لحين إعداد R2 الفعلي)
- **العزل الصارم للمفاتيح:**
  - مفاتيح التجهيز المؤقت: `uploads-staging/{fileId}/{versionId}/{sessionId}.pdf`
  - مفاتيح النسخ النهائية المعتمدة: `study-files/{fileId}/versions/{versionId}/{sha256Prefix16}_{sessionId}_{attemptId}.pdf`
- **نطاق السياسة المقترحة:** تُطبَّق قاعدة Lifecycle حصراً على البادئة `uploads-staging/` لحذف الكائنات وإلغاء الرفع الجزئي غير المكتمل بعد **24 ساعة (1 يوم)**، و**يُمنع منعاً باتاً** تطبيق أي قاعدة Lifecycle على البادئة `study-files/` أو على كامل الحاوية (Bucket-wide).
- **تنبيه مهم حول اختلاف الصيغ والوحدات:**
  - مثال JSON أدناه مخصَّص **حصراً لـ Cloudflare R2 Native REST API** (وتُحدَّد فيه قيمة `maxAge` داخل شرط `Age` بوحدة **الثواني**، لذا `86400` = يوم واحد).
  - تستخدم **واجهة لوحة تحكم Cloudflare (Dashboard UI)** و**واجهة S3-Compatible XML API** صيغاً ووحدات مختلفة؛ **فلا يجوز نسخ JSON أدناه إلى Dashboard أو إلى طلبات S3 XML**.
  - في **Cloudflare Dashboard** تُنشأ القاعدة من نموذج الواجهة عبر تحديد البادئة `uploads-staging/` ومدة `1` يوم لحذف الكائنات وإلغاء الرفع الجزئي (`Abort incomplete multipart uploads`).
  - في **S3-Compatible XML API** (`PutBucketLifecycleConfiguration` / AWS SDK / AWS CLI) تُستخدم بنية `<LifecycleConfiguration>` بوحدة **الأيام (`1`)** عبر `<Filter><Prefix>uploads-staging/</Prefix></Filter>` مع `<Expiration><Days>1</Days></Expiration>` و`<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation></AbortIncompleteMultipartUpload>`.

```json
// Cloudflare R2 Native REST API only (maxAge is in seconds: 86400 = 1 day)
{
  "rules": [
    {
      "id": "expire-abandoned-r2-staging-24h",
      "enabled": true,
      "conditions": {
        "prefix": "uploads-staging/"
      },
      "deleteObjectsTransition": {
        "condition": {
          "type": "Age",
          "maxAge": 86400
        }
      },
      "abortMultipartUploadsTransition": {
        "condition": {
          "type": "Age",
          "maxAge": 86400
        }
      }
    }
  ]
}
```

- **مثال مكافئ لواجهة S3-Compatible XML API (`PutBucketLifecycleConfiguration`, بوحدة الأيام `1`):**
```xml
<LifecycleConfiguration>
  <Rule>
    <ID>expire-abandoned-r2-staging-24h</ID>
    <Filter>
      <Prefix>uploads-staging/</Prefix>
    </Filter>
    <Status>Enabled</Status>
    <Expiration>
      <Days>1</Days>
    </Expiration>
    <AbortIncompleteMultipartUpload>
      <DaysAfterInitiation>1</DaysAfterInitiation>
    </AbortIncompleteMultipartUpload>
  </Rule>
</LifecycleConfiguration>
```

- **حدود سياسة Lifecycle ودور الخادم:**
  - تعمل قاعدة `uploads-staging/` كـ **شبكة أمان احتياطية (Fallback)** بعد 24 ساعة للملفات التي رُفعت وهُجرت دون استدعاء `/verify` أو إصدار فتحة رفع جديدة، وليست بديلاً عن التنظيف الفوري داخل `UploadVerificationService` عند الاعتماد (`CONSUMED`) أو الاستبدال (`SUPERSEDED`) أو الفشل/الانتهاء (`FAILED` / `EXPIRED`).
  - **لا تستطيع قاعدة R2 Lifecycle فحص حالة Firestore** قبل الحذف؛ لذا فاعتماد عزل البادئة `uploads-staging/` ومدة 24 ساعة (أطول بأضعاف من مهلة الجلسة 10 دقائق ومهلة قفل التحقق 5 دقائق) يضمن عدم مساسها بالجلسات النشطة أو النسخ النهائية المعتمدة في `study-files/`.
- **متطلبات مؤجلة (لا تُنفّذ الآن):** إذا استدعت الحاجة التشغيلية لاحقاً تنظيفاً أسرع من 24 ساعة للجلسات المهجورة التي لم يُطلب لها `/verify`، أو تنظيف وثائق `upload_sessions` التاريخية في Firestore، يُنفَّذ ذلك عبر **Firestore TTL Policy** على حقل `expiresAt` أو مهمة مجدولة مستقبلية (Cloud Run Job / Cloud Scheduler) لاحقاً.

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
