# نقطة التوقف — المرحلة الثامنة: تدقيق Backend D2 وربط Android (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** لم تُغلق المرحلة الثامنة نهائياً بعد، كما لم تُعتمد حزمة **Fix Pack 2G** نهائياً بعد؛ تم تنفيذ تدقيق وإصلاح **Backend D2** وربط **Android Native** واستكمال **Fix Pack 2F-R** ثم **Fix Pack 2G (Archive Safety Hardening & ViewModel Bypass Closure)** على الكود المصدري وقواعد الأمان والاختبارات، مع بقاء الاعتماد والإغلاق النهائي مرهونَين بتشغيل اختبارات محاكي Firebase وGradle فعلياً في بيئة تحتوي على JDK وAndroid SDK.  
- تم التحقق فعلياً من فحص TypeScript الساكن (`npm run lint` / `npx tsc --noEmit`) وبناء الويب (`npm run build`) واختبارات الوحدة غير المعتمدة على المحاكي (`seedCatalog` و`fixesVerification` و`pdfReaderDom`) وتطابق ملفَي `values/strings.xml` و`values-ar/strings.xml` والفحص الساكن لمصادر Kotlin.
- اختبارات Android Gradle (`testDebugUnitTest` / `assembleDebug` / `lintDebug` / `connectedDebugAndroidTest`) واختبارات محاكي Firebase (`npm run test:backend` / `npm run test:rules`) لم تُنفّذ فعلياً في هذه البيئة لعدم توفر JDK أو Android SDK أو محاكي Firebase المحلي (يُثبت ذلك صراحة دون أي ادعاء بتشغيلها أو إغلاق المرحلة الثامنة أو اعتماد Fix Pack 2G قبل إتمامها).

---

## 1. ملخص ما أُنجز فعلياً في استكمال المرحلة الثامنة

1. **تدقيق وإصلاح Backend D2 (`server/` + `tests/backend/backend.api.test.ts`):**
   - توحيد مهلة جلسة الرفع الافتراضية (`uploadSessionTtlSeconds = 600` ثانية = 10 دقائق) في `server/config/env.ts` تطابقاً مع `docs/STAGE_8_SECURITY_AND_R2_DESIGN.md`.
   - معالجة توافق الوثائق القديمة والتصفية الصارمة في `server/services/catalogService.ts` عبر `isHierarchyNodePublishedAndNotArchived` (تمييز نقاط `/api/catalog/*` العامة بشرط `isArchived === false` مع إبقاء الوثائق القديمة متاحة للإدارة في لوحة التحكم) ودعم `archiveStatus` الثلاثي (`UNARCHIVED` / `ARCHIVED` / `UNSPECIFIED`).
   - تشديد دورة التحقق والنشر في `server/services/uploadVerificationService.ts`: التحقق المسبق من `stagingKey` و`expectedSizeBytes` و`expectedSha256` قبل حجز القفل، والاعتماد الذري مع `Fencing` على `activeAttemptId`، ورفض النشر عند غياب أو عدم تطابق `head.contentLength` (`Fail-Closed`) ورفض حالة الأرشفة غير المحددة بـ `409 ARCHIVE_STATE_UNSPECIFIED`، مع إعادة التحقق من إثبات الجلسة والمحاولة داخل معاملة النشر لمنع TOCTOU.
   - توسيع `tests/backend/backend.api.test.ts` (Tests 1–11 + Test 16 ضمن Fix Pack 2F-R) لتغطية: مهلة الـ 600 ثانية، استبعاد المؤرشف عن الطلاب مقابل إظهار التوافق القديم للإدارة، شروط `ETag` (`412`)، رفض `head.contentLength == null` أو `expectedSha256` المتلاعب بها في `/publish`، والتحقق من عدم تنفيذ أي عمليات R2 عند رفض الحالات غير المحددة أو غياب/فساد المراجع.

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

3. **إصلاحات Fix Pack 2F-R (Repository Gap Closure):**
   - **F-2FR-1 (حالة الأرشفة الثلاثية في Android):** إضافة `ArchiveStatus` (`UNARCHIVED`, `ARCHIVED`, `UNSPECIFIED`) إلى `StudyFileMetadata` و`StudyFileVersionMetadata`، وتحديث `FirestoreAdminCatalogMapper` و`FirestoreCatalogMapper` و`FirestoreAdminCatalogDataSource` و`BackendAdminVersionDataSource` و`HybridAdminCatalogDataSource` لقراءة `archiveStatus` عند توفره أو تفسير `isArchived` بصورة ثلاثية دون تحويل الحقول المفقودة أو `null` أو غير المنطقية إلى `UNARCHIVED`.
   - **F-2FR-2 (واجهة الإدارة لحالة `UNSPECIFIED`):** تحديث `AdminPortalScreen.kt` و`AdminPortalDialogs.kt` و`AdminPortalViewModel.kt` لإظهار التحذير العربي الصريح («حالة الأرشفة غير محددة — هذه الوثيقة تحتاج إلى مراجعة قبل النشر»)، ومنع النشر أو الأرشفة/الاستعادة أو إضافة إصدار أو رفع PDF أو نشر الإصدار عند عدم تحديد حالة الأرشفة دون أي تعديل تلقائي على Firestore، مع إبقاء المعاينة والتعديل وفحص الإثباتات وإلغاء نشر الملف المنشور بالفعل متاحة.
   - **F-2FR-3 (ربط `ARCHIVE_STATE_UNSPECIFIED`):** إضافة `AdminValidationError.ARCHIVE_STATE_UNSPECIFIED` في `AdminFailure.kt` وربط رمز `409 ARCHIVE_STATE_UNSPECIFIED` في `BackendAdminVersionDataSource.kt` ورسائلها العربية في `AuthMessageResolver.kt` و`values/strings.xml` و`values-ar/strings.xml`.
   - **F-2FR-4 & F-2FR-5 (الاختبارات المضافة):** توسيع `FirestoreAdminCatalogMapperTest.kt`، `BackendHttpIntegrationTest.kt`، `AdminPortalViewModelTest.kt`، `FakeRepositories.kt`، `AdminPortalRtlTest.kt`، وإضافة عدادات R2 في `MockR2StorageAdapter` وتحديث Test 9 وإضافة Test 16 في `tests/backend/backend.api.test.ts`.

4. **إصلاحات Fix Pack 2G (Archive Safety Hardening & ViewModel Bypass Closure — غير معتمدة بعد حتى تشغيل الاختبارات):**
   - **F-2G-1 (إغلاق ثغرة `setStudyFileArchived`):** فحص قيمة `isArchived` داخل معاملة Firestore في `FirestoreAdminCatalogDataSource.setStudyFileArchived` ورفض الوثيقة المفقودة بـ `NOT_FOUND` ورفض حالة `UNSPECIFIED` بـ `ARCHIVE_STATE_UNSPECIFIED` دون أي كتابة، مع فرض الفحص نفسه عبر `HybridAdminCatalogDataSource` و`AdminCatalogRepositoryImpl` و`FakeAdminCatalogRepository` مع الحفاظ على تمرير `CancellationException` دون تغليف.
   - **F-2G-2 (تشديد `firestore.rules` + اختبارات القواعد):** إضافة شرط `('isArchived' in resource.data) && resource.data.isArchived is bool` في فرع تحديث الأرشفة/الاستعادة لـ `study_files`، وإضافة 6 اختبارات في القسم 10 من `tests/firestore.rules.test.ts` للتحقق من رفض أرشفة أو استعادة الوثائق المفقودة أو `null` أو النصية ورفض الاستعادة مع النشر مع إبقاء تعديل البيانات الوصفية وإلغاء النشر متاحين.
   - **F-2G-3 (مصدر حقيقة واحد لـ `ArchiveStatus`):** إزالة معامل البناء `isArchived` من `StudyFileMetadata` و`StudyFileVersionMetadata` وجعل `archiveStatus` (بافتراضي `ArchiveStatus.UNSPECIFIED`) مصدر الحقيقة الوحيد الذي تُشتق منه `isArchived` ودوال المساعدة، وتحديث جميع مواقع الإنشاء والنسخ في الإنتاج والاختبارات.
   - **F-2G-4 & F-2G-5 (تضييق `fromBackend` وإزالة الافتراضي غير الآمن):** اشتراط تطابق `(rawArchiveStatus == "UNARCHIVED" && rawIsArchived == false)` و`(rawArchiveStatus == "ARCHIVED" && rawIsArchived == true)` في `ArchiveStatus.fromBackend` وإرجاع `UNSPECIFIED` لما عداه، وإزالة القيمة الافتراضية لـ `archiveStatus` في `FirestoreAdminCatalogMapper.validateStudyFilePublishTransition` بحيث يأخذ `ArchiveStatus?` صريحاً ويرفض `null` و`UNSPECIFIED`.
   - **F-2G-6 (إغلاق مسارات التجاوز المباشر في `AdminPortalViewModel`):** تشديد `saveStudyFileVersion` و`uploadAndVerifyVersionPdf` و`requestPublishStudyFileVersion` و`requestToggleStudyFilePublished` و`requestToggleStudyFileArchived` ضد الاستدعاءات المباشرة والمحملة بحالة غير متطابقة أو `UNSPECIFIED`/`ARCHIVED` أو سياق ناقص، مع استثناء صريح لإلغاء نشر ملف منشور بالفعل وتعديلات البيانات الوصفية غير الحساسة.
   - **F-2G-7 & F-2G-8 (الاختبارات المضافة والمحدثة):** إنشاء `FirestoreAdminCatalogDataSourceTest.kt` لاختبار المعاملات مباشرًة دون كتابة عند الرفض، وفصل الحالتين 6 و7 في `AdminPortalRtlTest.kt` مع عدادات استدعاءات حقيقية و`performClick()`، وتوسيع `AdminPortalViewModelTest.kt` و`AdminCatalogRepositoryImplTest.kt` و`BackendHttpIntegrationTest.kt` و`FirestoreAdminCatalogMapperTest.kt` و`FirestoreCatalogMapperTest.kt`.

---

## 2. حالة التشغيل الفعلي والتوثيق الصادق

- **ما شُغّل ونجح فعلاً في بيئة الحاوية:**
  - `npx tsc --noEmit` (`npm run lint`) و`npm run build`.
  - اختبارات الوحدة التي لا تعتمد على محاكي Firebase: `src/data/seedCatalog.test.ts` و`src/domain/fixesVerification.test.ts` و`src/screens/pdfReaderDom.test.tsx`.
  - فحص تطابق موارد `android/app/src/main/res/values/strings.xml` و`values-ar/strings.xml` وفحص المراجع الساكن لملفات Kotlin.
- **ما تعذّر تشغيله لغياب الأدوات في الحاوية (ولذلك لا تُعد المرحلة الثامنة مكتملة بعد):**
  - `npm run test:backend` و`npm run test:rules` (يتطلبان Java JDK لتشغيل `firebase emulators:exec`).
  - `./gradlew testDebugUnitTest` و`lintDebug` و`assembleDebug` و`connectedDebugAndroidTest` (يتطلبان JDK + Android SDK 35 + Emulator؛ كما لا يملك `./gradlew` إذن التنفيذ مع غياب `java` في الحاوية).

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
