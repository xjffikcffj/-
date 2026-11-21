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
   - يُبطل أي جلسة `"PENDING"` أقدم لنفس الإصدار (`SUPERSEDED`)، وينشئ وثيقة `upload_sessions/{sessionId}` (`status = "PENDING"`, TTL قصير، `expectedSizeBytes`, `expectedSha256`) ويصدر Presigned `PUT` URL مقيداً بـ `uploads-staging/{fileId}/{versionId}/{sessionId}.pdf` و`Content-Length` و`Content-Type: application/pdf`.
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
     - تحقق `HeadObject(version.r2ObjectKey)` في R2 من وجود الكائن ومطابقة `fileSizeBytes`.
     - عدم أرشفة الملف الأب ولا الإصدار المستهدف.

---

## 3. سياسة نقاط الـ API والكاش المرحلية

| المسار | الأدوار المسموحة | التحقق والكاش |
| :--- | :--- | :--- |
| `GET /api/catalog/*` | الجميع (`Guest`, `Student`, `Admin`, `Owner`) | قراءة حية من Firestore دون كاش داخلي مبدئياً حتى إتمام الاختبارات. |
| `POST .../upload-slot`, `/verify`, `/publish` | `Admin`, `Owner` | `verifyIdToken(token, true)` + `decoded.role in ['admin', 'owner']`. |
| `GET /study-files/{f}/versions/{v}/stream` | الجميع (`Guest` فأعلى) | فحص حي لـ 5 مستويات دون كاش + `200 OK` دون `3xx` + `Content-Length` + `X-Content-Sha256`. |
| `GET /study-files/{f}/versions/{v}/download` | الحسابات المسجلة (`Student`, `Admin`, `Owner`) — ممنوع على `Guest` | `verifyIdToken(token, true)` غير مجهول + نفس الفحص الحي واستجابة `200 OK`. |
