# نقطة التوقف — المرحلة الثامنة: تدقيق Backend D2 وربط Android (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** لم تُغلق المرحلة الثامنة نهائياً بعد، ولم تبدأ المرحلة التاسعة. تم تحديث هذا الملف بنتائج جولة `:app:testDebugUnitTest` الفعلية الأخيرة في القسم `2.12` (نجاح كامل **`233 / 233`** اختباراً في `:app:testDebugUnitTest` عبر `35` ملف JUnit XML، مع `0` أخطاء تجميع و`0` فشل و`0` أخطاء و`0` متجاوز، بعد إصلاح إلغاء التنزيل المتوقف في ملف الإنتاج الوحيد `DownloadStreamFetcher.kt`؛ وبقاء `google-services.json` و`.git` غير موجودين في الحاوية). **تبقى المرحلة الثامنة مفتوحة حتى المراجعة النهائية.**
- السجل التاريخي للجولات السابقة في الحاوية: تم تنفيذ **دفعة الإصلاح المحدودة للأعطال الستة** لإزالة عوائق بناء Android واختبارات Backend، ونجحت وقتها `npm run lint` (`0`) و`npm run build` (`0`) و`npm run test:rules` (`17/17`) و`npm run test:backend` (`16/16`) و`:app:compileDebugKotlin`، ثم في جولة لاحقة أُنتج APK مع `google-services.json` بحجم `32,885,792` بايت وبعدها `33,661,462` بايت بعد فصل `BACKEND_NOT_CONFIGURED` (دون وجود `google-services.json` حالياً في الحاوية المستوردة). **تبقى المرحلة الثامنة مفتوحة.**

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

## 2.5. نتيجة مهمة تنظيف المستودع وتدقيق تجهيز ربط Firebase

1. **تنظيف GitHub و`.gitignore` وفحص الأسرار:**
   - تم تحديث `/.gitignore` و`/android/.gitignore` لتغطية `.gradle/` و`**/.gradle/` و`**/build/` و`*.apk` و`*.aab` و`*.ap_` و`*.keystore` و`*.jks` و`*.p12` و`*.pem` و`*.key` و`keystore.properties` و`local.properties` و`**/google-services.json` و`*service-account*.json` و`*firebase-adminsdk*.json`.
   - تم حذف مجلد التخزين المؤقت `android/.gradle/` (`8.11.1`, `buildOutputCleanup`, `vcs-1`) من مساحة العمل دون المساس بـ `android/gradle/wrapper/` (`gradle-wrapper.jar`, `gradle-wrapper.properties`) أو `gradlew` أو `gradlew.bat` أو ملفات المصدر والبناء.
   - ملاحظة بيئية: مجلد `.git` غير متوفر داخل حاوية AI Studio المستوردة، لذا يجب على المستخدم في نسخته المحلية تنفيذ `git rm -r --cached android/.gradle` (إن بقيت متتبعة في الفهرس) ثم دفع التعديلات إلى GitHub.
   - فحص المستودع لم يعثر على أي ملفات `.apk` أو `.aab` أو مفاتيح Keystore أو `.env` فعلية (عدا `.env.example`) أو `local.properties` أو `google-services.json` أو أسرار مضمنة في الكود.

2. **نتيجة تدقيق Firebase في الكود (قراءة وفحص فقط دون تعديل الكود الصحيح):**
   - **المشروع المعتمد فقط:** `student-learning-platform-2026` (`48673314803`)، حزمة `com.studentlearning.platform2026`، قاعدة Firestore `(default)`، المنطقة المرجعية `me-central1`. لم يُعثر على أي مرجع لمشروع Firebase آخر في أي ملف.
   - **الإضافات والاعتماديات:** `com.google.gms.google-services` بالإصدار `4.4.2` (يُفعّل تلقائياً داخل `android/app/build.gradle.kts` فور وجود `android/app/google-services.json`)، و`firebase-bom:33.7.0` مع `firebase-auth` و`firebase-firestore`.
   - **التهيئة والجلسات والتوكن وCustom Claims:** `FirebaseClientProvider` يتحقق من تطابق `projectId` بصيغة Fail-Closed، و`FirebaseAuthDataSource` + `AuthRepositoryImpl` يغطيان الدخول بالبريد وكلمة المرور، إنشاء حساب الطالب (`AdminRole.NONE`)، استعادة كلمة المرور، تسجيل الخروج، استعادة الجلسة عبر `AuthStateListener` مع تحديث قسري للـ Claims عند التشغيل، استخراج `ID Token` غير مجهول إلى Backend، وقراءة `claims["role"]` (`owner`/`admin`) مع التحقق السيرفري في `server/middleware/auth.ts` (`verifyIdToken(token, true)`).
   - **ملاحظة تدقيقية حول تجديد الرمز:** مسار الإدارة (`BackendAdminVersionDataSource`) يفرض `forceRefresh = true` عند كل طلب، بينما مسارات الكتالوج والبث والتنزيل تمرر `forceRefresh = false` وتعتمد على التجديد التلقائي من Firebase SDK مع رفض `401/403` دون إعادة محاولة تلقائية في `HttpPdfContentFetcher`/`HttpDownloadStreamFetcher` (يتاح أيضاً زر/دالة تحديث الجلسة يدوياً عبر `AccountViewModel.refreshSession`).
   - **ملف `android/app/google-services.json`:** تم نقل الملف الرسمي المرفق نفسه إلى المسار `android/app/google-services.json` (`720` بايت، دون تعديل محتواه أو إنشاء ملف بديل، ودون إبقاء نسخة داخل `public/`).
   - **التحقق من التطابق:** تطابقت البيانات غير السرية مع المشروع المعتمد (`project_id`: `student-learning-platform-2026`، `project_number`: `48673314803`، `package_name`: `com.studentlearning.platform2026`، `mobilesdk_app_id`: `1:48673314803:android:d9dcbc07559c76520aa486`، `storage_bucket`: `student-learning-platform-2026.firebasestorage.app`، `configuration_version`: `1`).
   - **حماية الملف والتفعيل التلقائي:** كلٌّ من `/.gitignore` و`/android/.gitignore` يمنعان رفع الملف إلى GitHub، وملف `android/app/build.gradle.kts` مهيأ لتفعيل `com.google.gms.google-services` تلقائياً بمجرد وجود الملف في المسار نفسه، بينما يتصل `FirebaseClientProvider` بالمشروع `student-learning-platform-2026` وقاعدة Firestore `(default)`.

3. **نتائج التحقق الفعلي من Gradle وFirebase والبناء (الجولة الحالية):**
   - **فحص سلامة الملف:** `android/app/google-services.json` موجود فعلياً (`720` بايت) ومطابق للمشروع `student-learning-platform-2026` (`48673314803`) والحزمة `com.studentlearning.platform2026` ومعرّف التطبيق `1:48673314803:android:d9dcbc07559c76520aa486`، دون أي نسخة متبقية في `public/` أو `public/assets/` أو `public/assets/aistudio/`، ومستثنى في كل من `/.gitignore` و`android/.gitignore`.
   - **تجهيز أدوات البناء في الحاوية:** `OpenJDK 17.0.21.1` (`/usr/lib/jvm/java-17-openjdk-amd64`) و`Android SDK` (`platforms;android-35`, `build-tools;35.0.0`, `build-tools;34.0.0`, `platform-tools` في `/opt/android-sdk`).
   - `sh ./android/gradlew -p android :app:processDebugGoogleServices --no-daemon`: **نجح (`BUILD SUCCESSFUL in 1m 36s`)** وولّد `android/app/build/generated/res/processDebugGoogleServices/values/values.xml` بالمعرّفات المعتمدة نفسها.
   - `sh ./android/gradlew -p android :app:compileDebugKotlin --no-daemon`: **نجح (`BUILD SUCCESSFUL in 1m 49s`)**.
   - `sh ./android/gradlew -p android :app:assembleDebug --no-daemon`: **نجح (`BUILD SUCCESSFUL in 3m 20s`)** وأنتج:
     - المسار: `android/app/build/outputs/apk/debug/app-debug.apk`
     - الحجم: **`32,885,792` بايت**
     - التحقق بـ `aapt2 dump badging` و`aapt2 dump strings`: الحزمة **`com.studentlearning.platform2026`** (`compileSdkVersion='35'`, `targetSdkVersion='35'`, `minSdkVersion='24'`) وموارد Firebase المضمنة تشير إلى **`student-learning-platform-2026`** و`48673314803` و`1:48673314803:android:d9dcbc07559c76520aa486`.
   - **الاتصال الحي بخدمات Firebase Auth / Firestore:** **لم يُختبر فعلياً بعد** (يحتاج اختباراً منفصلاً على جهاز/محاكي مع تفعيل الخدمات في Firebase Console).

---

## 2.6. نتيجة تدقيق جاهزية الاتصال بـ Firebase (Firebase Connection Readiness Audit)

- **الحالة العامة:** **المرحلة الثامنة لا تزال مفتوحة**؛ تم إتمام فحص جاهزية الكود والتهيئة دون تشغيل اختبارات اتصال حية بـ Production أو نشر قواعد/خوادم.
- **1. تهيئة Firebase داخل Android (`student-learning-platform-2026` / `48673314803` / `(default)` / `me-central1`):**
  - التهيئة القياسية تتم عبر `FirebaseInitProvider` والموارد المولّدة من `android/app/google-services.json` بواسطة `com.google.gms.google-services` (`4.4.2`) مع `firebase-bom:33.7.0` (`firebase-auth`, `firebase-firestore`).
  - `FirebaseClientProvider.kt` يفرض حارس `Fail-Closed`: يتحقق من تطابق `projectId == "student-learning-platform-2026"` قبل إرجاع `FirebaseAuth.getInstance(app)` أو `FirebaseFirestore.getInstance(app, "(default)")`، ولا توجد أي تهيئة يدوية مكررة أو استخدام `useEmulator` أو إشارة لمشروع آخر أو أسرار مكتوبة في كود Android أو مسارات Mock في `AppContainer.kt` (محاكاة `VITE_ENABLE_PREVIEW_DEMO` محصورة في معاينة الويب `src/` فقط).
- **2. مسارات المصادقة (Firebase Auth):**
  - المزوّد الفعلي الوحيد في Android هو **Email/Password** (`FirebaseAuthDataSource.kt`, `AuthRepositoryImpl.kt`, `SignInScreen.kt`, `SignUpScreen.kt`, `ForgotPasswordScreen.kt`): تسجيل الدخول، إنشاء حساب طالب (يُجبر دور `AdminRole.NONE` محلياً)، استعادة كلمة المرور، تسجيل الخروج، واستعادة الجلسة عند فتح التطبيق عبر `AuthStateListener` مع `readCurrentSession(forceTokenRefresh = true)` ومسح الامتيازات فوراً عند تبديل الحساب أو أخطاء التعطيل/فقدان الحساب، والتمييز الصارم للضيف (`GUEST` بدون توكن) عن المسجل (`STUDENT`/`ADMIN`/`OWNER`).
  - **متطلب Console غير قابل للتحقق من الكود:** يلزم التأكد في Firebase Console من تفعيل **Authentication -> Sign-in method -> Email/Password** قبل أول اختبار مصادقة حي.
- **3. مسارات Firestore ومنع الالتفاف (Zero-Bypass Check):**
  - **قراءة الكتالوج العام للطلاب/الضيوف:** مربوطة في `AppContainer.kt` بـ `BackendCatalogDataSource` (`/api/catalog/...`)؛ أما `FirestoreCatalogDataSource.kt` فغير موصول في الإنتاج (موجود للاختبار فقط)، لذا **لا يلتف التطبيق على Backend** نحو قراءة Firestore مباشرة، وهو ما يتطابق مع `firestore.rules` التي تحصر القراءة المباشرة في `isAdminOrOwner()`.
  - **قراءة/كتابة الإدارة عبر Firestore Client SDK (`FirestoreAdminCatalogDataSource` عبر `HybridAdminCatalogDataSource`):** قراءة وكتابة `branches` (لـ `owner` فقط) و`subjects` و`sections` و`study_files` metadata/أرشفة/إلغاء نشر/ترتيب.
  - **مسارات محجوزة للخادم فقط (`firestore.rules`):** كتابة `study_files/{fileId}/versions/*`، تعيين `study_files.isPublished = true` أو مؤشرات `activeVersion*`، وجميع عمليات `upload_sessions` (تُوجَّه عبر `BackendAdminVersionDataSource`).
  - **البيانات المحلية فقط:** التفضيلات (`PreferencesDataSource` عبر DataStore)، تقدم القراءة (`ReadingProgressDataSource`)، وسجلات/ملفات التنزيلات (`LocalPdfStorageManager`, `OfflineDownloadsDataSource`, WorkManager).
- **4. اتصال Backend D2 (`HAQIBATI_BACKEND_BASE_URL`):**
  - متغير `BuildConfig.HAQIBATI_BACKEND_BASE_URL` مضبوط افتراضياً على `""` (بدون URL وهمي)، و`AppContainer.kt` يترك `allowInsecureHttpForTesting = false`؛ لذا تقف مسارات الكتالوج والبث والتنزيل والرفع والإصدارات على وضع **Fail-Closed** (`AWAITING_SECURE_BROKER_INTEGRATION` / `FIREBASE_NOT_CONFIGURED` / `SECURE_SOURCE_NOT_CONFIGURED`).
  - مسارات Android تتطابق مع مسارات `server/app.ts` و`server/routes/*.ts` (`/api/catalog/*`, `/api/study-files/*/versions/*/{stream,download}`, `/api/admin/study-files/*/versions*`).
- **5. العوائق الحالية قبل الاختبار الحي الكامل (المتبقية بعد Auth):**
  - عدم نشر `firestore.rules` و`firestore.indexes.json` إلى Production بعد (مع ملاحظة أن مزوّد Email/Password وحساب المالك بـ Custom Claim موجودان ومفعّلان مسبقاً بحسب تأكيد المالك).
  - عدم تشغيل/نشر Backend D2 (`server.ts`) على رابط **HTTPS** عام (`HAQIBATI_BACKEND_BASE_URL`) وعدم توفر متغيرات الإنتاج السيرفرية (Service Account + مفاتيح Cloudflare R2).
  - بالنسبة لاختبار المحاكي المعزول (`npm run test:rules` و`npm run test:backend`): البيئة الحالية تحتوي `OpenJDK 17` و`firebase-cli 15.34.1` دون حزمة محاكي Firestore المخبأة أو `JDK 21+` المطلوب افتراضياً في التوثيق لتشغيل محاكي `firebase-tools`.

---

## 2.7. فحص Debug APK وتجهيز اختبار Firebase Auth الحقيقي (على الهاتف)

- **نتيجة فحص الملف `android/app/build/outputs/apk/debug/app-debug.apk` (دون إعادة البناء):**
  - **الوجود والحجم والتاريخ:** موجود فعلياً بحجم **`32,885,792` بايت** (`~31.36 MiB`)، تاريخ آخر تعديل **`2026-11-21 19:26:15 UTC`**، وبصمة `SHA-256`: `3dcac397f9615e31074c44708680742ea871c4464ef93363ff2e2d12b6a94f0f`.
  - **فحص الموارد وإعداد Firebase داخل APK (`aapt2 dump badging` / `resources` / `strings`):**
    - الحزمة: **`com.studentlearning.platform2026`** (`versionCode=1`, `versionName=0.1.0`, `minSdkVersion=24`, `targetSdkVersion=35`).
    - الأذونات المضمّنة: `android.permission.INTERNET` و`android.permission.ACCESS_NETWORK_STATE`.
    - موارد Firebase المترجمة داخل APK موجودة (`project_id`, `gcm_defaultSenderId`, `google_app_id`, `google_storage_bucket`, `google_api_key`, `google_crash_reporting_api_key`) وتطابق المشروع **`student-learning-platform-2026`** ورقم المشروع **`48673314803`** ومعرّف التطبيق **`1:48673314803:android:d9dcbc07559c76520aa486`** وحاوية التخزين **`student-learning-platform-2026.firebasestorage.app`**.
    - لم يلزم أي إعادة بناء لأن APK جاهز ومطابق تماماً.
- **مسار استعادة Custom Claims ودور المالك الموجود مسبقاً:**
  - عند تسجيل الدخول (`FirebaseAuthDataSource.signInWithEmailAndPassword`) أو استعادة الجلسة عند تشغيل التطبيق (`AuthRepositoryImpl` عبر `AuthStateListener`) أو فتح لوحة المشرفين (`AdminPortalViewModel.refreshSession(forceTokenRefresh = true)`), يستدعي التطبيق `currentUser.getIdToken(true)` ويقرأ `claims["role"]` (`AdminRole.fromClaim`) لإظهار دور **`مالك (Owner)`** في الإعدادات وفتح بوابة `AdminPortalScreen` دون إعادة تعيين أي صلاحية أو إنشاء حساب مالك جديد.
- **الإتاحة المباشرة للـ APK (دون ZIP ودون GitHub):** تم التحقق من أن خادم التطوير المحلي (`vite --port=3000`) يقدّم المسار `/android/app/build/outputs/apk/debug/app-debug.apk` مباشرة بالحالة `HTTP 200 OK` وبالحجم والبصمة نفسهما (`32,885,792` بايت).
- **حالة المرحلة:** **بانتظار تنفيذ المالك لاختبار Firebase Auth الحقيقي على هاتفه Android وتثبيت النتيجة**؛ تبقى **المرحلة الثامنة مفتوحة** ولم تبدأ المرحلة التاسعة.

---

## 2.8. تشخيص ظهور رسالة «الاتصال بـ Firebase غير مفعّل محلياً بعد» داخل تبويب المكتبة

- **النتيجة المؤكدة (Read-only، دون إعادة بناء APK):**
  - **لا يوجد أي خلل في `google-services.json` أو `FirebaseInitProvider` أو `FirebaseClientProvider` أو تطابق المعرّفات.**
    - `BuildConfig.APPLICATION_ID` و`AndroidManifest.xml package` (`Context.packageName`) و`google-services.json` متطابقة جميعاً على `com.studentlearning.platform2026`.
    - `FirebaseInitProvider` مسجل في `AndroidManifest.xml` داخل `app-debug.apk` بالسلطة `com.studentlearning.platform2026.firebaseinitprovider` (`initOrder=100`).
    - موارد Google Services المترجمة داخل APK تطابق `project_id = student-learning-platform-2026` و`google_app_id = 1:48673314803:android:d9dcbc07559c76520aa486` و`gcm_defaultSenderId = 48673314803`.
    - دالة `FirebaseStatusDataSource.inspectReadiness()` (أسطر `10-26`) تعيد `FirebaseConfigurationStatus.CONFIGURED_LOCALLY` عبر `FirebaseClientProvider.isFirebaseConfigured()` (`projectId == "student-learning-platform-2026"`).
  - **السبب الحقيقي المباشر لظهور الرسالة داخل «المكتبة»:**
    1. عند فتح «المكتبة» يستدعي `LibraryViewModel.kt:33` الدالة `catalogRepository.getPublishedBranches()` المربوطة في `AppContainer.kt:112-113` بـ **`BackendCatalogDataSource`** (وليس `FirestoreCatalogDataSource`).
    2. في `AppContainer.kt:90-96`، تكون قيمة `BuildConfig.HAQIBATI_BACKEND_BASE_URL` داخل APK الحالي **`""`**، فتصبح `backendHttpClient.isConfigured = false`.
    3. في `BackendCatalogDataSource.kt:136-141`، عند غياب رابط الـ Backend يرمي الكود `CatalogException` برمز السبب القديم **`CatalogFailureReason.FIREBASE_NOT_CONFIGURED`**.
    4. في `CatalogStateViews.kt:80-82`، يربط `CatalogErrorState` الرمز `CatalogFailureReason.FIREBASE_NOT_CONFIGURED` مباشرة بالنص `R.string.catalog_error_not_configured` (`values/strings.xml:55`, `values-ar/strings.xml:55`): «الاتصال بـ Firebase غير مفعّل محلياً بعد؛ يلزم إضافة ملف google-services.json وتكوين المشروع.»
  - **الخلاصة العملية:** الرسالة داخل «المكتبة» ناتجة عن **غياب `HAQIBATI_BACKEND_BASE_URL` (رابط Backend D2)** مع **إعادة استخدام رمز ورسالة `FIREBASE_NOT_CONFIGURED` القديمة لخطأ Backend**؛ ولا تمنع هذه الرسالة اختبار **Firebase Auth** في تبويب «الإعدادات» (تسجيل الدخول / إنشاء حساب طالب / استعادة كلمة المرور / دور المالك).
- **الإصلاح المحدود المقترح (لم يُنفَّذ بعد بانتظار الاعتماد):** فصل رمز خطأ عدم تهيئة الـ Backend (`BACKEND_NOT_CONFIGURED`) في `CatalogFailure.kt` و`BackendCatalogDataSource.kt` و`CatalogStateViews.kt` و`strings.xml` ليعرض رسالة دقيقة تخص رابط الـ Backend بدلاً من الإشارة إلى `google-services.json`.

---

## 2.9. تنفيذ الإصلاح المحدود لفصل `BACKEND_NOT_CONFIGURED` عن `FIREBASE_NOT_CONFIGURED`

- **الملفات المعدّلة والمضافة (دون تغيير `FirebaseClientProvider` أو `google-services.json` أو سلوك `Fail-Closed`):**
  1. `android/app/src/main/java/com/studentlearning/platform2026/domain/model/CatalogFailure.kt`: إضافة `BACKEND_NOT_CONFIGURED` إلى `CatalogFailureReason`.
  2. `android/app/src/main/java/com/studentlearning/platform2026/data/remote/backend/BackendCatalogDataSource.kt`: استخدام `CatalogFailureReason.BACKEND_NOT_CONFIGURED` في `executeCatalogHttpCall` عند `!httpClient.isConfigured` أو `BackendNotConfiguredException` بدلاً من `FIREBASE_NOT_CONFIGURED`.
  3. `android/app/src/main/res/values/strings.xml` و`android/app/src/main/res/values-ar/strings.xml`: إضافة `catalog_error_backend_not_configured` بالرسالتين الإنجليزية والعربية لتوضيح أن خدمة الكتالوج (`Backend D2`) غير مربوطة بعد وأن ذلك لا يعني وجود خلل في إعداد Firebase.
  4. `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/CatalogStateViews.kt`: معالجة `CatalogFailureReason.BACKEND_NOT_CONFIGURED` في تعبيرَي `when` للأيقونة والرسالة (`R.string.catalog_error_backend_not_configured`).
  5. `android/app/src/test/java/com/studentlearning/platform2026/data/remote/backend/BackendCatalogDataSourceTest.kt` (ملف اختبار جديد محدود): إثبات أن غياب `Backend URL` أو رفض رابط `http://` غير المشفر ينتج `CatalogFailureReason.BACKEND_NOT_CONFIGURED` وليس `FIREBASE_NOT_CONFIGURED`.
- **نتائج الاختبارات والبناء الفعلية:**
  - `sh ./android/gradlew -p android :app:compileDebugKotlin :app:assembleDebug --no-daemon`: **نجح (`BUILD SUCCESSFUL in 58s`)**.
  - **ملف Debug APK الجديد:** `android/app/build/outputs/apk/debug/app-debug.apk`
    - الحجم: **`33,661,462` بايت** (`2026-11-21 19:56:44 UTC`)
    - البصمة **`SHA-256`**: `d5d7725101effebcda0a33d0aea5bab840c3765aa0821f322209d1bf221c17d4`
    - التحقق بـ `aapt2 dump strings` و`curl -I http://127.0.0.1:3000/download/app-debug.apk`: الرسالتان الجديدة العربية والإنجليزية وموارد `student-learning-platform-2026` مضمّنة في APK، وخادم التطوير يقدمه بـ `HTTP 200 OK` و`Content-Type: application/vnd.android.package-archive` و`Content-Length: 33661462`.
  - `sh ./android/gradlew -p android :app:testDebugUnitTest --tests "com.studentlearning.platform2026.data.remote.backend.BackendCatalogDataSourceTest" --no-daemon`: **توقّف في مهمة `:app:compileDebugUnitTestKotlin` (`BUILD FAILED`)** بسبب أخطاء التصنيف المسبقة والمستقلة في ملفات الاختبار الأخرى الموثقة في القسم `2` (والتي لم تُمس التزاماً بعدم إصلاح مشكلات جانبية غير مرتبطة)، دون تسجيل أي خطأ تصنيف في `BackendCatalogDataSourceTest.kt`.
- **حالة المرحلة:** تبقى **المرحلة الثامنة مفتوحة** ولم تبدأ المرحلة التاسعة.

---

## 2.10. التحقق الفعلي من التعديلات الحالية (11 ملفاً) ونتائج التشغيل التسلسلي

1. **سلامة الملفات الـ 11 المعدّلة/المضافة ومراجعة التغييرات الإنتاجية الثلاثة:**
   - **تثبيت وجود وسلامة جميع الملفات الـ 11:** `vite.config.ts`، `AndroidManifest.xml`، `PdfReaderScreen.kt`، والملف المساعد الجديد `util/LoopbackHttpServer.kt`، وملفات الاختبار السبعة (`PdfSourceAndFetcherTest.kt`, `BackendHttpIntegrationTest.kt`, `FirestoreAdminCatalogDataSourceTest.kt`, `FirestoreAdminCatalogMapperTest.kt`, `AdminCatalogRepositoryImplTest.kt`, `OfflineDownloadsRepositoryImplTest.kt`, `SectionFilesViewModelTest.kt`).
   - **`vite.config.ts`:** إضافة مكوّن `serve-debug-apk-download` (`apply: 'serve'` فقط وفي الوضع غير الإنتاجي `!isProductionMode`) لقصر تنزيل APK على `/download/app-debug.apk` مع ترويسات `Content-Type: application/vnd.android.package-archive` و`Content-Disposition: attachment` و`X-Content-Type-Options: nosniff` و`Cache-Control: no-store`، ومنع الوصول (`403 Forbidden`) إلى `/android/**` و`/server/**` و`/tests/**` و`/docs/**` و`/build/**` و`/dist/**` و`/coverage/**` و`/scripts/**` وملفات الإعداد والأسرار و`/@fs/...` ومسارات `..%2f` وامتدادات `.apk/.aab/.keystore/.jks/.p12/.pem/.key/.gradle/.kts`. *(ملاحظة أمنية: مسار `/download/app-debug.apk` نفسه لا يفرض مصادقة تطبيقية داخل `vite.config.ts` عند تفعيل خادم التطوير، لذا يعتمد تقييد الوصول على طبقة معاينة التطوير، بينما لا يضمّ حزم الإنتاج `dist/` هذا المسار مطلقاً).*
   - **`android/app/src/main/AndroidManifest.xml`:** إضافة `xmlns:tools` وإدخال `androidx.startup.InitializationProvider` بوسم `tools:node="merge"` لإزالة `androidx.work.WorkManagerInitializer` (`tools:node="remove"`). وبمراجعة `manifest-merger-debug-report.txt` والملف المدمج النهائي، بقيت مهيئات `EmojiCompatInitializer` و`ProcessLifecycleInitializer` و`ProfileInstallerInitializer` سليمة بينما أُزيل `WorkManagerInitializer` ليعمل `Configuration.Provider` في `HaqibatiApplication` (`HaqibatiWorkerFactory`) و`WorkManagerTestInitHelper` دون تعارض.
   - **`android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`:** استبدال استدعاءَي `produceState` في `PdfPageItem` (أسطر `903-926`) بـ `remember` مع `mutableFloatStateOf`/`mutableStateOf` و`LaunchedEffect` وبنفس مفاتيح التبعية (`session.fileId`, `pageIndexZeroBased`, `targetWidthPx`) مع الإبقاء على `lastValidBitmap` لتفادي الوميض عند التكبير، مما أزال أخطاء Lint دون تغيير سلوك القارئ.

2. **نتائج التشغيل التسلسلي للأوامر الثلاثة لـ Android:**
   - **1) `:app:testDebugUnitTest`:** **فشل (`BUILD FAILED` في `:app:compileDebugUnitTestKotlin`, `exit 1`)**.
     - *ما تم التحقق منه:* تجميع الإنتاج وجميع ملفات الاختبار الستة الأخرى و`LoopbackHttpServer.kt` وكتلة `customBroker` (`860-905`) في `AdminCatalogRepositoryImplTest.kt` خلت تماماً من أخطاء التجميع.
     - *الخطأ الفعلي المتبقي وسببه:* 6 أخطاء تجميع في `AdminCatalogRepositoryImplTest.kt` في **3 استدعاءات سابقة (`382`, `430`, `536`)** للدالة المساعدة `fakeVersionStorageBroker { ... }`:
       `Argument type mismatch: actual type is 'Function0<AppResult<VerifiedPdfBinaryProof>>', but 'Function2<String, String, AppResult<VerifiedPdfBinaryProof>>' was expected` و`Argument type mismatch: actual type is 'PdfBrokerConfigurationStatus', but 'AppResult<VerifiedPdfBinaryProof>' was expected`.
       **السبب:** `fakeVersionStorageBroker` (أسطر `50-72`) يأخذ معاملين دواليين (`statusProvider` أولاً ثم `verifyProvider` ثانياً)، فارتبط الـ trailing lambda `{ ... }` بالمعامل الثاني `verifyProvider` بدلاً من `statusProvider = { ... }`. وبالتزام التوجيه بعدم إعادة تعديل الملفات الـ 11، لم يُمس الملف، وبالتالي **لم تُنفَّذ اختبارات JVM فعلياً بعد**.
   - **2) `:app:lintDebug`:** **نجح (`BUILD SUCCESSFUL`) — `0 errors, 74 warnings`** (مؤكَّد في `android/app/build/reports/lint-results-debug.txt` و`lint-results-debug.xml`). زالت الأخطاء الثلاثة السابقة بالكامل.
   - **3) `:app:assembleDebug`:** **نجح (`BUILD SUCCESSFUL in 44s`)**.
     - المسار: `android/app/build/outputs/apk/debug/app-debug.apk`
     - الحجم الفعلي: **`32,883,996` بايت** (`32M`)
     - بصمة **`SHA-256`**: `a6683a6047b16a5f0b1867cbff8318a6b9e17ce65887a5f46848f20dea2ca6e2`
     - التحقق عبر `aapt2 dump badging`: الحزمة `com.studentlearning.platform2026` (`versionCode=1`, `versionName=0.1.0`, `minSdk=24`, `targetSdk=35`).
     - **ملاحظة:** بُني هذا الـ APK في الحاوية **بدون `android/app/google-services.json`** (الذي لا يُرفع إلى GitHub ولا يوجد في مساحة العمل المستوردة؛ لذا طبع Gradle: `Notice: android/app/google-services.json is not present yet`).

3. **نتائج اختبار تنزيل APK وحماية المعاينة والويب:**
   - **تنزيل APK الفعلي من خادم التطوير (`http://127.0.0.1:3000/download/app-debug.apk`):** `HTTP/1.1 200 OK`، `Content-Type: application/vnd.android.package-archive`، `Content-Disposition: attachment; filename="app-debug.apk"`، `Content-Length: 32883996`، وطابقت بصمة `SHA-256` للملف المنزّل البصمة الأصلية (`a6683a6047b16a5f0b1867cbff8318a6b9e17ce65887a5f46848f20dea2ca6e2`) وتطابق الحجم (`32,883,996` بايت).
   - **فحص حظر التسريب عبر خادم المعاينة (`curl`):** `200` للمسارات `/` و`/src/main.tsx` و`/download/app-debug.apk` فقط، و**`403 Forbidden`** لـ 16 مساراً حساساً (`/android/app/build.gradle.kts`, `/android/app/build/outputs/apk/debug/app-debug.apk`, `/android/app/build/reports/lint-results-debug.txt`, `/android/app/build/outputs/logs/manifest-merger-debug-report.txt`, `/server/config/env.ts`, `/tests/backend/backend.api.test.ts`, `/docs/STAGE_8_D2_BACKEND_RUNBOOK.md`, `/.env.example`, `/package.json`, `/vite.config.ts`, `/STAGE_8_CHECKPOINT.md`, `/@fs/app/applet/vite.config.ts`, `/@fs/app/applet/android/app/build.gradle.kts`, `/@fs/app/applet/android/app/build/outputs/apk/debug/app-debug.apk`, `/src/..%2fvite.config.ts`, `/download/../vite.config.ts`). كما خلت `dist/` تماماً من أي `.apk` أو ملفات Android (`dist/index.html`, `dist/assets/index-*.js`, `dist/assets/index-*.css` فقط).
   - **فحوصات Node/TypeScript المنفصلة المنفذة في هذه البيئة:** `npm run lint` (`tsc --noEmit`: `0`)، `npm run build` (`0`)، واختبارات الوحدة غير المعتمدة على المحاكي (`seedCatalog.test.ts`, `fixesVerification.test.ts`, `entrypointAndRateLimit.test.ts`, `r2StagingLifecycle.test.ts`: **`17/17` ناجحاً**).

4. **مهام لم تكتمل بعد في المرحلة الثامنة (انظر القسمَين `2.11` و`2.12` لنتائج `:app:testDebugUnitTest` النهائية):**
   - ~~معالجة السبب الجذري في كود الإنتاج `DownloadStreamFetcher.kt:76` للاختبار الأخير المتبقي (`1 / 233`)~~ (**تمت المعالجة والتحقق في القسم `2.12` ونجحت `233 / 233` الاختبارات**).
   - تنفيذ `git rm -r --cached android/.gradle` في المستودع المحلي/GitHub (مجلد `.git` غير متاح داخل حاوية AI Studio المستوردة؛ مع استمرار حماية `android/.gradle/` في `/.gitignore` و`/android/.gitignore`).
   - تشغيل `npm run test:rules` و`npm run test:backend` في هذه الجولة الجديدة (يتطلبان `JDK 21+` وحزمة محاكي Firestore، بينما بيئة الحاوية الحالية تقتصر على `OpenJDK 17`).
   - تشغيل `connectedDebugAndroidTest` على جهاز أو محاكي Android، وتضمين `android/app/google-services.json` عند بناء نسخة الاختبار الحي، ثم الاختبار الحي مع Firebase وBackend D2.

---

## 2.11. إغلاق عائق تجميع اختبارات Android وتشغيل `:app:testDebugUnitTest` الفعلي

1. **الملفات المعدّلة (ملفات الاختبار وإعدادات Unit Test فقط — دون المساس بملفات الإنتاج):**
   - `android/app/src/test/java/com/studentlearning/platform2026/data/repository/AdminCatalogRepositoryImplTest.kt`: تمرير المعاملين المُسمّيَين `statusProvider = { ... }` و`verifyProvider = { ... }` في الاستدعاءات الثلاثة لـ `fakeVersionStorageBroker` (الأسطر `382`, `438`, `564`).
   - `android/app/build.gradle.kts`: إضافة `testOptions { unitTests.isReturnDefaultValues = true }` فقط داخل `android { ... }` لتفادي فشل تهيئة `SparseArray` داخل `FirebaseFirestoreException$Code` في اختبارات JVM المحلية، دون تعديل منطق الإنتاج أو الاعتماديات أو بناء APK.
   - `android/app/src/test/java/com/studentlearning/platform2026/presentation/admin/AdminPortalViewModelTest.kt`: تمرير `FirestoreReferenceTarget` بدلاً من النصوص الخام في إعداد `legacy study file`.
   - `android/app/src/test/java/com/studentlearning/platform2026/data/repository/AuthRepositoryImplTest.kt`: ضبط `readSessionHandler` لـ `"stu-1"` وتمرير `observerScope` يستخدم `testDispatchers.io` مع `advanceUntilIdle()` لضمان اشتراك مستمع تبديل الحساب قبل البث.
   - `android/app/src/test/java/com/studentlearning/platform2026/presentation/downloads/DownloadsViewModelTest.kt`: مطابقة الترتيب المتوقع في الاختبار مع `sortedByDescending { it.downloadedAtEpochMillis }`.
   - `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModelTest.kt`: تغليف `fileAGate.await()` بـ `withContext(NonCancellable)` لتمرير النتيجة المتأخرة إلى `PdfReaderViewModel` وإثبات إغلاق الجلسة السابقة `sessionA`.
   - `android/app/src/test/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImplTest.kt`: إغلاق نطاق `CoroutineScope` الخاص بنسخة `DataStore` الأولى (`cancelAndJoin()`) قبل إنشاء نسخة `DataStore` الثانية على الملف نفسه.
   - `android/app/src/test/java/com/studentlearning/platform2026/data/repository/OfflineDownloadsRepositoryImplTest.kt`: عزل مجلد التخزين لكل `Harness` (`tempFolder.newFolder()`)، وإضافة `scheduler.awaitIdle()` بعد `advanceUntilIdle()` لانتظار اكتمال حفظ `DataStore`، وإنشاء ملفات اليتامى المصطنعة بعد بدء التنزيل النشط `v2` حتى لا يمسحها تنظيف البداية قبل فحص منتصف التنزيل.
   - `android/app/src/test/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModelTest.kt`: استخدام `ViewModelStore.clear()` في اختبار انقضاء الشارة، وضبط نطاق `DataStore` بـ `dispatchers.io` مع انتظار تحديث `uiState` عبر `advanceUntilIdle()` وتجنب السباق مع قراءة `DataStore` الأولية.
   - `STAGE_8_CHECKPOINT.md`: هذا التحديث.
   - *التحقق من القيود:* لم يتم تعديل أي ملف في `android/app/src/main/**` ولا `PdfReaderScreen.kt` ولا `AndroidManifest.xml` ولا `vite.config.ts` ولا Firebase Auth أو Firestore أو Backend D2، ولم تُضف `google-services.json` ولم تُحذف أو تُعطّل أو تُتجاوز أي اختبارات.

2. **نتيجة تشغيل `sh ./android/gradlew -p android :app:testDebugUnitTest --no-daemon --console=plain`:**
   - **تجميع اختبارات Android (`:app:compileDebugUnitTestKotlin`):** **نجح بالكامل (`0` أخطاء تجميع؛ لم تظهر أي أخطاء تجميع جديدة).**
   - **حالة التنفيذ النهائية:** **`BUILD FAILED`** بسبب اختبار واحد فقط.
   - **الأعداد الفعلية المؤكدة من تقارير JUnit XML:**
     - الإجمالي: **`233`** اختباراً
     - الناجحة: **`232`** اختباراً
     - الفاشلة: **`1`** اختبار واحد
     - المتجاوزة (`skipped`): **`0`**
   - **تفاصيل الاختبار الوحيد الفاشل والعائق المتبقي:**
     - الاختبار: `OfflineDownloadsRepositoryImplTest > slow stalled http download cancellation closes socket releases resources and deletes part file` (`OfflineDownloadsRepositoryImplTest.kt:1300,1400`, الخطأ: `java.lang.AssertionError`).
     - السبب المثبَت في كود الإنتاج: الدالة `DownloadStreamFetcher.kt:76-80` تستدعي `currentCoroutineContext()[Job]?.invokeOnCompletion { ... }` بالقيمة الافتراضية `onCancelling = false`، لذا لا يُستدعى `activeConnection.disconnect()` فور إلغاء الـ Job بينما خيط الإدخال محجوز في `input.read(buffer)`، فلا يُغلق المقبس من جهة العميل قبل انتهاء مهلة الخادم (`serverSawClosedSocket == false` عند السطر `1400`). لم يُعدّل `DownloadStreamFetcher.kt` التزاماً بقاعدة عدم تعديل ملفات الإنتاج.

3. **حالة Git ومرحلة المشروع:**
   - فحص `git status` أعاد: `fatal: not a git repository (or any of the parent directories): .git` (الحاوية المستوردة لا تحتوي مجلد `.git`).
   - **حالة المرحلة الثامنة:** **مفتوحة** بانتظار المراجعة النهائية وإقرار معالجة `DownloadStreamFetcher.kt:76` وتشغيل اختبارات الجهاز/المحاكي والربط الحي لاحقاً.

---

## 2.12. إصلاح إلغاء التنزيل المعلّق في `DownloadStreamFetcher.kt` واجتياز جميع اختبارات `:app:testDebugUnitTest` (`233 / 233`)

1. **السبب الجذري المثبَت في `DownloadStreamFetcher.kt`:**
   - استخدام `invokeOnCompletion { ... }` بالقيمة الافتراضية (`onCancelling = false`) لم يُطلق إغلاق الاتصال فور بدء الإلغاء أثناء حجز خيط التنزيل داخل `input.read(buffer)`.
   - إضافةً إلى ذلك، على عميل OpenJDK `sun.net.www.protocol.http.HttpURLConnection` (المستخدم في اختبارات JVM عبر `http://` المحلية)، تستدعي `disconnect()` داخلياً `MeteredStream.close()` التي تطلب قفل `ReentrantLock readLock` نفسه المحتجَز داخل `input.read(buffer)`؛ مما يسبّب تعليق خيط الإلغاء نفسه حتى انتهاء مهلة الخادم إذا استُدعيت `disconnect()` أثناء القراءة المتوقفة.

2. **الإصلاح المنفّذ في ملف الإنتاج الوحيد (`android/app/src/main/java/com/studentlearning/platform2026/data/download/DownloadStreamFetcher.kt`):**
   - إضافة `@OptIn(InternalCoroutinesApi::class)` المتوافق مع `kotlinx-coroutines` (`1.9.0`) واستخدام `invokeOnCompletion(onCancelling = true, invokeImmediately = true)` لتنفيذ خطاف الإلغاء فور بدء الإلغاء.
   - إضافة حارس `AtomicBoolean` (`disconnected` داخل `closeConnectionOnce`) لمنع الإغلاق المكرر بين خطاف الإلغاء وكتلة `finally` ودون آثار جانبية.
   - إضافة `TrackingSSLSocketFactory` لاتصالات `HttpsURLConnection` لإغلاق مقابس SSL المتتبَّعة مباشرةً قبل `activeConnection.disconnect()`.
   - تقييد مهلة شريحة القراءة (`JDK_HTTP_READ_SLICE_MS = 200ms` مع التجميع التراكمي لـ `SocketTimeoutException` حتى `READ_TIMEOUT_MS = 30_000ms` وفحص `currentCoroutineContext().ensureActive()`) على اتصالات OpenJDK HTTP غير المشفرة فقط (`usesJdkSunHttp`) دون تغيير سلوك Android OkHttp أو HTTPS (`readTimeout = 30_000ms`)، مع تفادي تعليق خيط الإلغاء على `MeteredStream.readLock` أثناء `isReadingBody.get() == true` ليُغلق التدفق والمقبس نظيفاً خلال `<= 200ms` وتُحذَف ملفات `.part` وتُمرَّر `CancellationException` دون تحويلها إلى خطأ عادي.
   - لم يُمس أي ملف إنتاج آخر، ولم تُعدَّل الاختبارات الناجحة من الجولة السابقة.

3. **نتائج التشغيل الفعلي النهائية:**
   - **تشغيل الاختبار الفردي أولاً (`--tests "...OfflineDownloadsRepositoryImplTest.slow stalled http download cancellation closes socket releases resources and deletes part file"`):** **نجح (`BUILD SUCCESSFUL`)**.
   - **تشغيل المجموعة الكاملة (`sh ./android/gradlew -p android :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain`):** **نجح بالكامل (`BUILD SUCCESSFUL in 1m 21s`, `24 actionable tasks: 24 executed`)**.
   - **الأعداد النهائية المؤكدة من تقارير JUnit XML (`35` ملف `TEST-*.xml`):**
     - الإجمالي: **`233`**
     - الناجحة (`passed`): **`233`**
     - الفاشلة (`failures`): **`0`**
     - الأخطاء (`errors`): **`0`**
     - المتجاوزة (`skipped`): **`0`**
     - من ضمنها `OfflineDownloadsRepositoryImplTest`: **`21 / 21`** ناجحاً (`failures=0, errors=0, skipped=0`)، و`AdminCatalogRepositoryImplTest`: **`20 / 20`** ناجحاً.
   - **حالة المرحلة الثامنة:** **تبقى المرحلة الثامنة مفتوحة** حتى المراجعة النهائية (مع استمرار بقاء اختبارات الجهاز/المحاكي `connectedDebugAndroidTest` و`google-services.json` والربط الحي خارج بيئة الحاوية الحالية، وعدم بدء المرحلة التاسعة أو دعم iOS).

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
