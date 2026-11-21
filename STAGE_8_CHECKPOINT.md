# نقطة التوقف — المرحلة الثامنة: تدقيق Backend D2 وربط Android (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** لم تُغلق المرحلة الثامنة نهائياً بعد، ولم تبدأ المرحلة التاسعة. تم تنفيذ **دفعة الإصلاح المحدودة للأعطال الستة** لإزالة عوائق بناء Android واختبارات Backend، ونجحت اختبارات `npm run lint` (`0`) و`npm run build` (`0`) و`npm run test:rules` (`17/17`, `0`) و`npm run test:backend` (`16/16`, `0`) وبناء `:app:compileDebugKotlin` وإنتاج ملف Debug APK فعلي (`android/app/build/outputs/apk/debug/app-debug.apk`, `32,883,248` بايت، `0`). بقيت مهام `:app:testDebugUnitTest` (`exit 1`) و`:app:lintDebug` (`exit 1`) معلقة بسبب أخطاء مسبقة ومستقلة عن الإصلاحات الستة في ملفات اختبارات الوحدة الـ 7 (`:app:compileDebugUnitTestKotlin`) و3 أخطاء Lint مسبقة في `AndroidManifest.xml` و`PdfReaderScreen.kt`، إضافةً إلى تعذر `connectedDebugAndroidTest` لغياب `/dev/kvm` والأجهزة المتصلة.
- تم التوقف عند حدود دفعة الأعطال الستة دون فتح سلسلة إصلاحات متسلسلة للأعطال المستقلة المذكورة حتى تتم مراجعتها واعتماد خطوتها التالية.

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

5. **مراجعة التحقق وسد الفجوات لـ Fix Pack 2H (غير معتمدة بعد حتى تشغيل اختبارات المحاكي وGradle):**
   - **ما ثُبت وجوده مسبقاً في المستودع دون إعادة كتابة:**
     - `AdminPortalViewModel.kt` + `AdminPortalViewModelTest.kt`: إزالة fallback إلى الكائن الممرر في `requestPublishStudyFileVersion` / `requestToggleStudyFilePublished` / `requestToggleStudyFileArchived`، والتحقق من تطابق `fileId` و`id` للإصدار، ومنع `UNSPECIFIED` في النشر/الأرشفة/الاستعادة، وإبقاء إلغاء نشر ملف منشور متاحاً، وإعادة التحقق داخل التنفيذ دون الاعتماد على حالة قديمة.
     - `FirestoreAdminCatalogDataSource.kt`: تمرير المعاملات عبر `AdminCatalogFirestoreClient` و`FirebaseAdminCatalogFirestoreClient` في الإنتاج، والقراءة والتحقق داخل المعاملة قبل أي كتابة، والتحقق من أرشفة الملف والإصدار، ومطابقة إثبات PDF، وتسلسل أرقام الإصدارات، وعزل تعديل البيانات الوصفية، وحفظ `CancellationException`.
     - `firestore.rules` و`firestore.indexes.json` و`tests/firestore.rules.test.ts` وميزات الطالب المحلية (`NewVersionBadgePolicy`، تعدد إصدارات التنزيل المحلي ونزاهتها، حفظ تقدم القراءة محلياً عبر تبديل الحساب والأرشفة، وتفضيلات اللغة والوضع الليلي المحلية).
   - **الفجوات الفعلية المغلقة في هذه الجولة فقط (3 ملفات كود/اختبار + هذه الوثيقة):**
     - `FirestoreAdminCatalogDataSourceTest.kt`: إضافة اختبار رفض نشر إصدار مرجعه `Fields.FILE_ID` تابع لملف آخر (`other-file` -> `AdminValidationError.EMPTY_PARENT_REFERENCE` مع 0 عمليات كتابة)، وإضافة تأكيدات `createStudyFileVersion` لرفض تكرار رقم الإصدار (`INVALID_VERSION_NUMBER`) ورفض `publishAsActive = true` (`VERSION_PDF_BINARY_MISSING`) مع 0 كتابات إضافية.
     - `BackendCatalogDataSource.kt`: تشديد `fetchPublishedStudyFiles` في وضع Fail-Closed لرفض العناصر المتناقضة أو غير الصالحة (`isArchived` موجود ولا يساوي `false`، أو `isPublished` موجود ولا يساوي `true`، أو `sectionId` لا يطابق القسم المطلوب، أو `archiveStatus` لا يطابق `"UNARCHIVED"` حرفياً).
     - `BackendHttpIntegrationTest.kt`: توسيع اختبار التصفية العامة ليشمل العناصر المتناقضة (`isArchived: true`، `isArchived: "false"`، `isPublished: false`، و`sectionId: "sec-other"`).

---

## 2. حالة التشغيل الفعلي والتوثيق الصادق

- **الأدوات المتوفرة مسبقاً والتي جُهزت محلياً في بيئة الحاوية:**
  - متوفر مسبقاً: `Node.js v22.23.2`، `npm 10.9.8`، `firebase-cli 15.34.1`، وغلاف `android/gradlew` (Gradle 8.11.1).
  - جُهز محلياً (مجاني دون حسابات/أسرار): **Eclipse Temurin JDK 21.0.13.1+2** (`/opt/java/jdk-21`)، و**Android SDK CLI 12.0** مع `platforms;android-35` و`build-tools;35.0.0` و`build-tools;34.0.0` و`platform-tools 37.0.3` (`adb 1.0.41`) في `/opt/android-sdk`، ومحاكي Firestore المحلي (`cloud-firestore-emulator-v1.22.0.jar`).

- **الأعطال الستة التي عولجت في الدفعة المحدودة والملفات المعدّلة (6 ملفات):**
  1. `android/gradle/libs.versions.toml` و`android/app/build.gradle.kts`: إضافة `implementation(libs.guava)` (`com.google.guava:guava:32.1.3-android` المطابق لما يجلبه Firebase في runtime) إلى مسار التصنيف لأن `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` كان يخفي `ListenableFuture` عن `DownloadTaskScheduler.kt` دون تغيير جدولة WorkManager.
  2. `android/app/src/main/java/com/studentlearning/platform2026/data/repository/AdminCatalogRepositoryImpl.kt` (الأسطر `402-403`): مطابقة المعاملَين المُسمّيَين في استدعاء `requestVersionUploadSlot` مع عقد `PdfVersionStorageBroker` (`fileSizeBytes` و`checksumSha256`) مع بقاء التحقق من الحجم وSHA-256 والإثبات دون تغيير.
  3. `android/app/src/main/java/com/studentlearning/platform2026/presentation/downloads/DownloadsScreen.kt` (الأسطر `19, 147`): استبدال الأيقونة غير الموجودة `Icons.Rounded.LockOutline` بـ `Icons.Rounded.Lock` المتوفرة في حزمة Material Icons Extended.
  4. `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModel.kt` (السطر `15`): إضافة استيراد `com.studentlearning.platform2026.domain.model.StudyFileOfflineState`.
  5. `tests/backend/backend.api.test.ts` — Test 12 (الأسطر `127-169`): تعديل تدفق `MockR2StorageAdapter` ليطبّق `destroy()` و`return()` و`[Symbol.asyncIterator]()` مع دالة `markClosed()` متسقة عند الإغلاق المبكر من `DeliveryService` قبل أول `.next()` دون إضعاف التحقق من `contentLength == null`.
  6. `tests/backend/backend.api.test.ts` — Test 16 (السطر `325`): تقديم `currentNowMs += 10_000_000` بين الاختبارات في `beforeEach` لعزل نوافذ `InMemoryRateLimiter` لكل اختبار دون تغيير حدود الإنتاج في `server/middleware/rateLimit.ts`.

- **نتائج الأوامر السبعة الفعلية بعد الإصلاح:**
  1. `npm run lint` (`tsc --noEmit`): **نجح — رمز خروج `0`**.
  2. `npm run build` (`vite build --mode production`): **نجح — رمز خروج `0`** (`dist/index.html 1.22 kB`، `dist/assets/index-DJmymc7v.css 30.78 kB`، `dist/assets/index-C7rASqja.js 350.65 kB`).
  3. `npm run test:rules`: **نجح — `17 / 17` اختباراً ضمن 9 مجموعات، رمز خروج `0`**.
  4. `npm run test:backend`: **نجح — `16 / 16` اختباراً (`Test 1` إلى `Test 16`), رمز خروج `0`**.
  5. `./android/gradlew testDebugUnitTest --no-daemon` (بـ `sh ./android/gradlew -p android ...`): نجح `:app:compileDebugKotlin` بالكامل، ثم **فشل (`EXIT_CODE=1`) في `:app:compileDebugUnitTestKotlin`** بسبب أخطاء مسبقة ومستقلة عن الإصلاحات الستة في 7 ملفات اختبار وحدة:
     - `PdfSourceAndFetcherTest.kt:50` و`BackendHttpIntegrationTest.kt` (66 خطأ: استخدام SAM lambda على `AuthTokenProvider` غير `fun interface` + `HttpServer`/`HttpExchange` غير متوفرة على `android.jar` bootclasspath).
     - `FirestoreAdminCatalogDataSourceTest.kt` (16 خطأ: استخدام SAM lambda على `FirestoreProvider` غير `fun interface`).
     - `FirestoreAdminCatalogMapperTest.kt:817` (غياب استيراد `StudyFileMetadata`).
     - `AdminCatalogRepositoryImplTest.kt` (7 أخطاء: عدم تطابق توقيع مزيف `PdfVersionStorageBroker`).
     - `OfflineDownloadsRepositoryImplTest.kt` (55 خطأ: `AuthorizedRemoteUrl` ناقصة `cacheKey`، و`HttpServer`، وتوقيع قديم لـ `DownloadStreamFetcher`).
     - `SectionFilesViewModelTest.kt` (4 أخطاء: `NETWORK_OR_UNAVAILABLE` و`AuthorizedRemoteUrl` ناقصة `headers`).
  6. `./android/gradlew lintDebug --no-daemon`: **فشل (`EXIT_CODE=1`) — `3 errors, 74 warnings`** (مشاكل مسبقة ومستقلة في `AndroidManifest.xml:7` [`RemoveWorkManagerInitializer`] و`PdfReaderScreen.kt:903,915` [`ProduceStateDoesNotAssignValue`]؛ محفوظة في `android/app/build/reports/lint-results-debug.txt`).
  7. `./android/gradlew assembleDebug --no-daemon`: **نجح — `BUILD SUCCESSFUL`، رمز خروج `0`**، وأُنتج ملف Debug APK الفعلي:
     - المسار: `android/app/build/outputs/apk/debug/app-debug.apk`
     - الحجم: **`32,883,248` بايت** (`32M`).
- **ما تعذّر تشغيله وبقي معلقاً:**
  - `connectedDebugAndroidTest`: متعذر في الحاوية لغياب `/dev/kvm` والأجهزة المتصلة عبر `adb` (فضلاً عن بقاء `testDebugUnitTest` و`lintDebug` بانتظار قرار إصلاح الأخطاء المستقلة).
  - الربط والاختبار الحي مع Firebase Production وCloudflare R2 وCloud Run Backend (`android/app/google-services.json` و`.env` وتعيين `HAQIBATI_BACKEND_BASE_URL` وتوقيع Release).

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
