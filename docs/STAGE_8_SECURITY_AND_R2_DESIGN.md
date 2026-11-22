# التصميم الأمني المعتمد للمرحلة الثامنة — قواعد Firestore ودورة Cloudflare R2 (مشروع «حقيبتي»)

> هذا المستند يثبت المرجع المعماري والأمني المعتمد بعد المراجعات الثلاث للمرحلة الثامنة، وقبل بدء تنفيذ الخادم وربط Android بالـ Backend.

---

## 1. سياسة الوصول والقواعد (`firestore.rules`)

1. **مصفوفة الأدوار (`Custom Claims` عبر `request.auth.token.role`):**
   - `owner`: قراءة/إنشاء/تعديل `branches`، قراءة/إنشاء/تعديل/نشر/إلغاء نشر `subjects`، إدارة `sections` و`study_files` (عدا النشر وتعديل مؤشرات الإصدار).
   - `admin`: قراءة `branches` فقط (ممنوع من الإنشاء/التعديل/النشر/إلغاء النشر)، إدارة `subjects` (ممنوع من إلغاء نشر مادة منشورة `true -> false`)، إدارة `sections` و`study_files` (مسودة، تعديل بيانات، أرشفة/استعادة، إلغاء نشر فقط).
   - `student` / `guest`: لا وصول مباشر لقراءة أو كتابة مجموعات الإدارة أو الفهرس في Firestore؛ قراءة الفهرس المنشور تمر عبر Cloud Run API (`/api/catalog/...`).
   - `versions` و`upload_sessions`: ممنوعة من أي إنشاء/تعديل/حذف مباشر عبر العميل؛ القراءة على `versions` متاحة لـ `admin`/`owner` فقط، و`upload_sessions` مغلقة تماماً على العملاء.
   - `delete`: ممنوع كلياً لجميع الأدوار على كل المجموعات.

2. **الوثائق القديمة (`Legacy Compatibility`):**
   - تحافظ القواعد على قبول تعديل أو إعادة ترتيب الوثائق القديمة التي تفتقر إلى `createdByUid`/`createdAt` أو تحتوي `nameAr` دون `name` (عند عدم تعديل `name`)، مع منع اختلاق أو تغيير حقول التدقيق القديمة وفرض الحقول الكاملة (`name`, `createdByUid`, `createdAt`) بصرامة على أي إنشاء جديد.

---

## 2. دورة التحقق من ملفات PDF في Cloudflare R2 (التصميم المُثبَّت)

### تنسيق المفاتيح
- **مفتاح التجهيز المؤقت (Staging):**
  `uploads-staging/{fileId}/{versionId}/{sessionId}.pdf`
  *(حيث `sessionId` معرّف UUID كامل غير مختصر).*
- **المفتاح النهائي الحصري للخادم (Final Immutable Key):**
  `study-files/{fileId}/versions/{versionId}/{sha256Prefix16}_{sessionId}_{attemptId}.pdf`
  - `sessionId`: **UUID كامل** غير مختصر (لا يُقتطع إلى 8 خانات أبداً).
  - `attemptId`: **UUID كامل فريد** لكل محاولة تحقق جديدة (`POST .../verify`) يمنع أي تداخل أو استبدال بين عامل متأخر وعامل جديد.
  - العملاء لا يحصلون مطلقاً على أي رابط `PUT` أو `DELETE` ضمن بادئة `study-files/`.

### تسلسل التحقق والاعتماد والنشر
1. **`POST /api/admin/study-files/{fileId}/versions/{versionId}/upload-slot`:**
   - يتحقق ذرياً داخل معاملة Firestore من أن الملف غير مؤرشف، والإصدار غير مؤرشف، وغير منشور (`!isPublished`)، وغير موثق (`binaryVerificationStatus != "VERIFIED"`)، ولا توجد جلسة `"VERIFYING"` نشطة.
   - يُبطل أي جلسة `"PENDING"` أقدم لنفس الإصدار (`SUPERSEDED`)، وينشئ وثيقة `upload_sessions/{sessionId}` (`status = "PENDING"`, TTL = 10 دقائق افتراضياً `600` ثانية، `expectedSizeBytes`, `expectedSha256`) ويصدر Presigned `PUT` URL مقيداً بـ `uploads-staging/{fileId}/{versionId}/{sessionId}.pdf` و`Content-Length` و`Content-Type: application/pdf`.
2. **الرفع المباشر لـ `stagingKey`:** يرفع العميل إلى `stagingKey` مباشرة.
3. **`POST /api/admin/study-files/{fileId}/versions/{versionId}/verify` (`{ uploadSessionId }`):**
   - **المرحلة أ — معاملة قفل المحاولة (`Acquire Lock Tx`):**
     - إذا كانت `session.status == "CONSUMED"` و`version.binaryVerificationStatus == "VERIFIED"` و`version.verifiedUploadSessionId == sessionId` و`session.finalObjectKey == version.r2ObjectKey` و`version.verifiedAttemptId == session.activeAttemptId`، يُعاد فوراً **`200 OK` بنفس الإثبات المخزن (Idempotent Retry)**.
     - إذا كانت `session.status == "VERIFYING"` و`verifyingExpiresAt > now`، يُرفض الطلب بـ `409 Conflict`.
     - إذا كانت `PENDING` (سارية) أو `VERIFYING` منتهية المهلة (`verifyingExpiresAt <= now`)، يولّد الخادم **`attemptId` (UUID كاملاً جديداً)**، ويرفع `attemptCount`، ويضبط `status = "VERIFYING"`, `activeAttemptId = attemptId`, `verifyingExpiresAt = now + 5m`.
   - **المرحلة ب — بث التجهيز والنسخ الشرطي:**
     - يبث الخادم `GetObject(stagingKey)` للتحقق من ترويسة `%PDF-` والطول الدقيق و`SHA-256` والتقاط `stagingETag`.
     - ينسخ داخل R2 عبر `CopyObject` إلى `attemptFinalKey` (`..._{sessionId}_{attemptId}.pdf`) مع **`CopySourceIfMatch: stagingETag`**. (`ETag` ليس بديلاً عن `SHA-256` بل قيد منع تغيير المصدر أثناء النسخ).
   - **المرحلة ج — التحقق الحاسم على بايتات الكائن النهائي نفسها:**
     - ينفّذ الخادم `GetObject(attemptFinalKey)` ويبث البايتات النهائية من بدايتها لنهايتها للتحقق من `%PDF-` والطول و**`SHA-256(attemptFinalKey) === expectedSha256`**.
   - **المرحلة د — معاملة الاعتماد الذري مع التحقق من ملكية القفل (`Fencing Commit Tx`):**
     - تعيد المعاملة قراءة `session` و`version` و`file`.
     - إذا تغيّر القفل (`session.status != "VERIFYING"` أو **`session.activeAttemptId != attemptId`**) أو صار الإصدار موثقاً/منشوراً/مؤرشفاً، يرفض العامل القديم الاعتماد فوراً دون لمس `versions`.
     - عند تطابق `session.activeAttemptId == attemptId`، تُسجَّل ذرياً:
       - على `versions/{versionId}`: `r2ObjectKey = attemptFinalKey`, `fileSizeBytes`, `checksumSha256`, `binaryVerificationStatus = "VERIFIED"`, `verifiedUploadSessionId = sessionId`, `verifiedAttemptId = attemptId`, `binaryVerifiedAt = serverTimestamp()`.
       - على `upload_sessions/{sessionId}`: `status = "CONSUMED"`, `finalObjectKey = attemptFinalKey`, `consumedAt = serverTimestamp()`.
   - **المرحلة هـ — تنظيف `stagingKey` بعد الاعتماد (غير مُفشِل):**
     - حذف `stagingKey` يتم داخل `try/catch` غير مُفشِل بعد نجاح المعاملة؛ وتتكفل قاعدة Lifecycle Rule في R2 بحذف `uploads-staging/` بعد 24 ساعة.
   - **المرحلة و — الحماية من حذف الكائن النهائي عند الفشل/الغموض:**
     - **يُمنع** حذف `attemptFinalKey` عند تعذر قراءة Firestore أو الشك في حالة الاعتماد.
     - لا يُحذف `attemptFinalKey` تنظيفياً إلا بعد قراءة طازجة ناجحة من Firestore تؤكد أن:
       `session.finalObjectKey != attemptFinalKey` و`version.r2ObjectKey != attemptFinalKey` و`!version.isPublished`.
     - لا تُغيَّر `session.status` إلى `"FAILED"` إلا داخل معاملة تتحقق من `session.activeAttemptId == attemptId`.
4. **`POST /api/admin/study-files/{fileId}/versions/{versionId}/publish` (وقبل أي اعتماد للنشر):**
   - يجب التحقق من **جميع الشروط التالية مجتمعة** داخل معاملة/تحقق النشر قبل تفعيل الإصدار أو الملف:
     - `version.binaryVerificationStatus === "VERIFIED"` و`session.status === "CONSUMED"`.
     - **`session.finalObjectKey === version.r2ObjectKey`**.
     - **`version.verifiedUploadSessionId === sessionId`**.
     - **`version.verifiedAttemptId === session.activeAttemptId`**.
     - تحقق `HeadObject(version.r2ObjectKey)` في R2 من وجود الكائن ومطابقة `fileSizeBytes` (يرفض النشر إذا كانت `head.contentLength == null` أو لا تطابق الحجم الموثق).
     - عدم أرشفة الملف الأب ولا الإصدار المستهدف.

---

## 3. سياسة نقاط الـ API والكاش المرحلية ومسارات Android المعتمدة

| المسار | الأدوار المسموحة | التحقق والكاش |
| :--- | :--- | :--- |
| `GET /api/catalog/*` | الجميع (`Guest`, `Student`, `Admin`, `Owner`) | قراءة حية من Firestore (مع استبعاد `isArchived == true`) دون كاش داخلي. |
| `POST .../versions`, `.../upload-slot`, `.../verify`, `.../publish` | `Admin`, `Owner` | `verifyIdToken(token, true)` + `decoded.role in ['admin', 'owner']`. |
| `GET /api/study-files/{f}/versions/{v}/stream` | الجميع (`Guest` فأعلى) | فحص حي لـ 5 مستويات دون كاش + `200 OK` دون `3xx` + `Content-Length` + `X-Content-Sha256`. |
| `GET /api/study-files/{f}/versions/{v}/download` | الحسابات المسجلة (`Student`, `Admin`, `Owner`) — ممنوع على `Guest` | `verifyIdToken(token, true)` غير مجهول + نفس الفحص الحي واستجابة `200 OK`. |

---

## 4. حالة التنفيذ المصدري ومتطلبات التفعيل الخارجي (غير منشور/غير متصل بعد)

1. **اكتمال الشيفرة مقارنةً بجاهزية التشغيل الفعلي:**
   - **على مستوى الشيفرة (Code Completeness):** يطبّق `server/services/uploadVerificationService.ts` العزل الكامل بين `uploads-staging/` و`study-files/`، مع مهلة افتراضية `600` ثانية لجلسة الرفع و`300` ثانية لقفل التحقق (`server/config/env.ts`)، و`ETag` (`CopySourceIfMatch`)، وقراءة البايتات النهائية لإثبات `%PDF-` والطول الدقيق و`SHA-256`، و`Fencing Commit Tx` على `activeAttemptId`، ومنع حذف الكائن النهائي عند غموض حالة Firestore، وإغلاق `/publish` بوضع `Fail-Closed`.
   - **على مستوى الأمان الشبكي ونقطة R2 (`TRUST_PROXY` و`R2_ENDPOINT`):**
     - يضبط الخادم `TRUST_PROXY` افتراضياً على `false` (لا ثقة بأي بروكسي أو `X-Forwarded-For`)، ويرفض `'true'` و`'all'` صراحةً. ولا يُسمح برقم قفزات محدد (`1..10`) إلا خلف بروكسي موثوق غير قابل للتجاوز على مستوى الشبكة وبعد التحقق الدقيق من عدد القفزات الفعلي.
     - تفرض دالتا `validateR2Endpoint` (`server/config/env.ts`) و`resolveR2Endpoint` (`server/storage/r2Storage.ts`) استخدام `HTTPS` الصالح فقط عند تحديد `R2_ENDPOINT` (مع رفض `HTTP` والروابط المشوهة أو المحتوية على بيانات اعتماد)، مع الحفاظ على العنوان الافتراضي `https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com` عند غياب `R2_ENDPOINT` دون أي مسار إنشاء بديل لـ `S3Client`.
   - **على مستوى التشغيل الفعلي (Operational Readiness — غير مكتمل):** بيئة العمل المستوردة الحالية خالية من `Java JDK` و`Android SDK` ومحاكي Firestore و`google-services.json`؛ لذا لم تُنفَّذ فيها اختبارات المحاكي (`npm run test:backend` بإجمالي `36` اختباراً للحزم الثلاث، و`npm run test:rules` بـ `17` اختباراً) أو اختبارات وبناء Android (`testDebugUnitTest`، `lintDebug`، `assembleDebug`، `connectedDebugAndroidTest`)، واقتصرت الاختبارات المنفذة فعلياً هنا على `npm run lint` و`npm run build` واختبارات وحدة الخادم المستقلة (`20 / 20`) وملفات اختبارات الويب (`3 / 3`).
2. **وضع Cloudflare R2 والخدمات السحابية الحالي (غير منشور وغير متصل):**
   - **Cloudflare R2:** **غير مُنشأ** (لا Bucket ولا API Tokens ولا قاعدة Lifecycle).
   - **Backend D2 وFirestore Production:** الخادم **غير منشور** على Cloud Run، وقواعد/فهارس Firestore **غير منشورة** إلى Production. تعمل مسارات Backend وAndroid بوضع **Fail-Closed** (`R2_STORAGE_NOT_CONFIGURED` / `AWAITING_SECURE_BROKER_INTEGRATION` / `BACKEND_NOT_CONFIGURED`).
   - عند اعتماد المالك لبدء التفعيل الخارجي لاحقاً، تُنشأ حاوية R2 واحدة مع عزل البادئتين وتُفعَّل قاعدة Lifecycle **حصراً على `uploads-staging/` بعد 24 ساعة** (وفق `docs/STAGE_8_D2_BACKEND_RUNBOOK.md`) دون المساس بـ `study-files/` أو كامل الحاوية مطلقاً.
3. **إجراءات خارجية تتطلب موافقة المالك الصريحة قبل التنفيذ:**
   - نشر `firestore.rules` و`firestore.indexes.json` إلى مشروع Firebase Production.
   - إنشاء Cloudflare R2 Bucket وتوليد API Tokens وتفعيل قاعدة Lifecycle لـ `uploads-staging/`.
   - نشر Backend D2 على Cloud Run، وإعداد Edge / WAF Rate Limiting و`TRUST_PROXY` وفق مسار الشبكة الفعلي غير القابل للتجاوز، وتزويد تطبيق Android بـ `android/app/google-services.json` و`HAQIBATI_BACKEND_BASE_URL` لإعادة البناء وتشغيل جميع الاختبارات في بيئة مكتملة.
