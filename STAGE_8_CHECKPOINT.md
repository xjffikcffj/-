# نقطة التوقف — المرحلة الثامنة: تدقيق Backend D2 وربط Android (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** اكتمل تدقيق وإصلاح **Backend D2** (`server/` + `tests/backend/backend.api.test.ts`) واستكمال ربط تطبيق **Android Native (`حقيبتي`)** بواجهات Backend D2 وفحص RBAC وتدفق رفع والتحقق من PDF.  
- تم التحقق برمجياً وساكنياً من قواعد `firestore.rules` وطبقة Node/Express (`npm run lint` و`npm run build`).
- اختبارات Android Gradle (`testDebugUnitTest` / `assembleDebug` / `lintDebug` / `connectedDebugAndroidTest`) واختبارات محاكي Firebase (`npm run test:backend` / `npm run test:rules`) لم تُنفّذ فعلياً في هذه البيئة لعدم توفر JDK 21+ أو Android SDK أو محاكي Firebase المحلي (يُثبت ذلك صراحة دون أي ادعاء بتشغيلها).

---

## 1. ملخص ما أُنجز فعلياً في استكمال المرحلة الثامنة

1. **تدقيق وإصلاح Backend D2 (`server/` + `tests/backend/backend.api.test.ts`):**
   - توحيد مهلة جلسة الرفع الافتراضية (`uploadSessionTtlSeconds = 600` ثانية = 10 دقائق) في `server/config/env.ts` تطابقاً مع `docs/STAGE_8_SECURITY_AND_R2_DESIGN.md`.
   - معالجة توافق الوثائق القديمة والتصفية الصارمة في `server/services/catalogService.ts` عبر `isHierarchyNodePublishedAndNotArchived` (تمييز نقاط `/api/catalog/*` العامة بشرط `isArchived === false` مع إبقاء الوثائق القديمة متاحة للإدارة في لوحة التحكم).
   - تشديد دورة التحقق والنشر في `server/services/uploadVerificationService.ts`: التحقق المسبق من `stagingKey` و`expectedSizeBytes` و`expectedSha256` قبل حجز القفل، والاعتماد الذري مع `Fencing` على `activeAttemptId`، ورفض النشر عند غياب أو عدم تطابق `head.contentLength` (`Fail-Closed`) مع إعادة التحقق من إثبات الجلسة والمحاولة داخل معاملة النشر لمنع TOCTOU.
   - توسيع `tests/backend/backend.api.test.ts` (Tests 1–11) لتغطية: مهلة الـ 600 ثانية، استبعاد المؤرشف عن الطلاب مقابل إظهار التوافق القديم للإدارة، شروط `ETag` (`412`)، ورفض `head.contentLength == null` أو `expectedSha256` المتلاعب بها في `/publish`.

2. **ربط Android بـ Backend D2 ومسارات البث والتنزيل والرفع:**
   - **تكوين الـ URL الآمن (`android/app/build.gradle.kts`):** إضافة `BuildConfig.HAQIBATI_BACKEND_BASE_URL` المقروء من Gradle property أو متغير بيئة (افتراضي `""` بوضع Fail-Closed دون HTTP صريح أو أسرار R2).
   - **مزود التوكن الآمن (`FirebaseAuthDataSource.kt`):** إضافة واجهة `AuthTokenProvider.getIdTokenOrNull(forceRefresh)` التي تُرجع `null` للزائر وتجلب Firebase ID Token الصالح للحسابات المسجلة فقط.
   - **طبقة الشبكة Kotlin الخالصة (`data/remote/backend/`):**
     - `BackendJson.kt`: محلل ومولّد JSON متوافق مع RFC-8259 بلغة Kotlin خالصة (دون اعتماد على `org.json` أو `android.*` ليعمل بسلاسة في اختبارات JVM).
     - `BackendHttpClient.kt`: عميل `HttpURLConnection` يفرض HTTPS (ويسمح بـ HTTP للمضيف المحلي في الاختبارات فقط عند تفعيل `allowInsecureHttpForTesting = true`) مع حقن `Authorization: Bearer`، ومنع إعادة التوجيه (`instanceFollowRedirects = false`)، والبث المباشر لـ `PUT` (`setFixedLengthStreamingMode`).
     - `BackendCatalogDataSource.kt`: تطبيق `CatalogRemoteDataSource` على `/api/catalog/...` مع ربط `activeVersionFileSizeBytes` و`activeVersionChecksumSha256` وتصنيف أخطاء `401/403`.
     - `BackendAdminVersionDataSource.kt` و`HybridAdminCatalogDataSource`: ربط دورة الإصدارات والرفع والتحقق والنشر وإعادة نشر ملف الأب عبر `/api/admin/study-files/{fileId}/versions*` مع بقاء `branches`/`subjects`/`sections`/`study_files` metadata والأرشفة/إلغاء النشر والترتيب على `FirestoreAdminCatalogDataSource` المتوافق مع `firestore.rules`.
     - `PdfUploadUriReader.kt`: قراءة ملف PDF المختار من Android Storage Access Framework بثاً دون تحميل الملف كاملاً في الذاكرة والتحقق من `%PDF-` والحد الأقصى `100 MiB` وحساب `SHA-256`.
   - **مسارات البث والتنزيل والرفع الموثق:**
     - `BrokeredPdfFileSourceDataSource` (`PdfFileSourceDataSource.kt`): مسار `/api/study-files/{f}/versions/{v}/stream` للبث (متاح للضيف والطالب والإدارة) ومسار `/api/study-files/{f}/versions/{v}/download` للتنزيل (يتطلب `Bearer` غير مجهول وإلا يرمي `UNAUTHORIZED_ACCESS`)، وتم تحديث `DownloadExecutionEngine` لاستدعاء `resolveDownloadPdfSource`.
     - `OfflineDownloadsRepositoryImpl`: تثبيت `activeVersionFileSizeBytes` و`activeVersionChecksumSha256` في طلبات التنزيل وإعادة المحاولة لحماية النزاهة.
     - `BrokeredPdfVersionStorageDataSource`: ربط `BackendVersionUploadClient` للرفع عبر جلسات Staging والتحقق، مع بقاء حالة `AWAITING_SECURE_BROKER_INTEGRATION` ما لم يكتمل كل من عنوان HTTPS ومزود الرفع.
   - **تطبيق RBAC والحد الأقصى للوصف وواجهة الرفع في لوحة المشرف:**
     - فرض حد 1000 حرف لوصف الملف (`AdminCatalogValidator` + `AdminValidationError.DESCRIPTION_TOO_LONG` + `AuthMessageResolver`) مطابقة لـ `firestore.rules`.
     - حصر إنشاء وتعديل ونشر وترتيب الفروع وإلغاء نشر المواد بدور `Owner` في كل من `AdminCatalogRepositoryImpl` و`AdminPortalViewModel` و`AdminPortalScreen` مع إشعارات عربية واضحة.
     - إضافة زر «رفع والتحقق من PDF» مع `ActivityResultContracts.OpenDocument()` ومؤشر التقدم وشارة التحقق داخل `AdminFileVersionsDialog` و`AdminPortalScreen`.

3. **الاختبارات المكتوبة / المحدّثة في Android:**
   - `BackendHttpIntegrationTest.kt` (جديد — اختبار HTTP حي باستخدام `HttpServer` على loopback).
   - تحديث `FakeRepositories.kt`، `PdfSourceAndFetcherTest.kt`، `AdminCatalogValidatorTest.kt`، `AdminCatalogRepositoryImplTest.kt`، `AdminPortalViewModelTest.kt`، و`AdminPortalRtlTest.kt`.

---

## 2. حالة التشغيل الفعلي والتوثيق الصادق

- **ما شُغّل ونجح فعلاً في بيئة الحاوية:** فحص TypeScript/Vite/ESLint للخادم وبناء Web، والفحص الساكن الشامل على ملفات Kotlin وموارد `strings.xml` في `values` و`values-ar`.
- **ما تعذّر تشغيله لغياب الأدوات في الحاوية:**
  - `npm run test:backend` و`npm run test:rules` (يتطلبان Java JDK لتشغيل `firebase emulators:start`).
  - `./gradlew testDebugUnitTest` و`assembleDebug` و`lintDebug` و`connectedDebugAndroidTest` (يتطلبان JDK + Android SDK 35 + Emulator).

---

## 3. أوامر التحقق النهائية في البيئة المكتملة

```bash
# 1) اختبارات الخادم والقواعد (مع توفر JDK 21+):
npm run lint
npm run build
npm run test:rules
npm run test:backend

# 2) اختبارات وبناء تطبيق Android (مع توفر JDK 17+ و Android SDK 35):
cd android
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```
