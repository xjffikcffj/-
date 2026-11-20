# نقطة التوقف — المرحلة السابعة: إدارة تنزيل الملفات والعمل دون اتصال بالإنترنت (`STAGE_7_CHECKPOINT.md`)

**حالة المرحلة:** مكتملة برمجياً (Android Native — Kotlin + Jetpack Compose)، غير معتمدة تشغيلياً لعدم توفر JDK / Android SDK ووسيط Cloudflare R2 في بيئة التنفيذ الحالية.

---

## 1. ملخص ما تم تنفيذه في المرحلة السابعة

1. **صلاحية التنزيل ومنع الزوار (`OfflineDownloadsRepositoryImpl`، `PdfDownloadWorker`، `SectionFilesViewModel`):**
   - يتصفح الزائر المحتوى المنشور ويقرؤه عبر الإنترنت دون تسجيل دخول.
   - عند محاولة التنزيل، تتحقق طبقة المستودع (`OfflineDownloadsRepositoryImpl.startDownload` / `retryDownload`) وطبقة العامل الخلفي (`PdfDownloadWorker.doWork`) وطبقة العرض (`SectionFilesViewModel`) من الجلسة الفعلية عبر `AuthRepository.requireAuthenticatedSession()` وحقل الصلاحية الموجود `AppUserRole.canDownloadOfflineFiles` / `AdminSession.canDownloadOfflineFiles`.
   - يُرفض الزائر صراحةً برمز `DownloadFailureReason.UNAUTHENTICATED_GUEST` حتى عند استدعاء دوال المستودع مباشرة، دون منح أي صلاحيات تلقائية أو تعديل قواعد الأدوار المعتمدة في المرحلة الخامسة.

2. **سلوك الملف داخل المكتبة دون إنشاء صفحة تنزيلات جديدة (`SectionFilesScreen`، `SectionFilesViewModel`، `SectionFilesUiState`):**
   - بقيت التجربة بالكامل داخل شاشة ملفات القسم في المكتبة مع الاحتفاظ بموضع الملف الطبيعي في القائمة.
   - عدم وجود نسخة محلية للإصدار النشط: يظهر زرا **«تصفح»** (يفتح القارئ عبر الإنترنت) و**«تنزيل»** (يبدأ الحفظ المحلي دون اتصال).
   - أثناء التنزيل: يظهر شريط التقدم (`LinearProgressIndicator`) مع حالة النص ونسبة التقدم المئوية (`جاري التنزيل… %1$d%%`) عند توفرها أو مؤشر غير محدد عند غياب `Content-Length`، مع زر **«إلغاء»**.
   - بعد نجاح التنزيل والتحقق: يظهر مؤشر **«تم التنزيل»** وحجم النسخة المحلية، ويصبح الضغط على البطاقة أو زر **«فتح دون اتصال»** يفتح النسخة المحلية مباشرة دون الحاجة إلى إنترنت.
   - عند فشل جلب قائمة القسم من الشبكة (`CatalogFailureReason.NETWORK_UNAVAILABLE` أو غيره)، تُعرض الملفات التي سبق تنزيلها في القسم نفسه للفتح المباشر دون اتصال.

3. **دورة حياة التنزيل الموثوقة عبر `WorkManager` (`DownloadTaskScheduler`، `PdfDownloadWorker`، `HaqibatiWorkerFactory`، `DownloadExecutionEngine`):**
   - الاعتماد على `WorkManager` (`androidx.work:work-runtime-ktx:2.10.0`) مع `Constraints(NetworkType.CONNECTED)` و`HaqibatiWorkerFactory` المحقون عبر `Configuration.Provider` في `HaqibatiApplication` لاستمرار التنزيل بغض النظر عن بقاء الشاشة مفتوحة أو إغلاق القارئ.
   - منع التكرار للنسخة نفسها `(fileId, versionId)` عبر فحص السجل المحلي الصالح على القرص أولاً، ثم فحص حالة العمل الفريد (`ExistingWorkPolicy.KEEP` للبدء، و`ExistingWorkPolicy.REPLACE` عند `retryDownload`).
   - الإلغاء، إعادة المحاولة، التمييز الصريح لأخطاء الشبكة والخادم وصلاحيات الوسيط وعدم كفاية المساحة وفشل الكتابة وفشل التحقق، وتنظيف ملفات `.part` المؤقتة دائماً داخل كتلة `finally` عند الفشل أو الإلغاء دون المساس بالنسخ المكتملة.

4. **التخزين المحلي الآمن والتحقق من السلامة (`LocalPdfStorageManager`، `OfflineDownloadsDataSource`):**
   - الحفظ حصراً داخل المساحة الخاصة للتطبيق (`context.filesDir`):
     - النسخ المكتملة المتحقَّق منها: `<filesDir>/haqibati_offline_pdfs/<hex(fileId)>/<hex(versionId)>/document.pdf`
     - الملفات المؤقتة أثناء التحميل: `<filesDir>/haqibati_offline_tmp/<hex(fileId)>_<hex(versionId)>_<uuid>.part`
   - التحقق الصارم قبل الاعتماد (`verifyDownloadedTempFile`): عدم خلو الملف، مطابقة الحجم مع `expectedFileSizeBytes` أو `Content-Length` عند توفره، فحص ترويسة `%PDF-`، وحساب بصمة `SHA-256` ومطابقتها مع `expectedChecksumSha256` أو ترويسة `X-Content-Sha256` عند توفرها.
   - فحص المساحة الحرة قبل وأثناء التدفق (`ensureSufficientStorage`) والتعامل مع أخطاء `ENOSPC` / الكتابة.

5. **ارتباط التنزيل بالإصدار والخصوصية واستقلال الأجهزة:**
   - المفتاح المعتمد للتخزين والبيانات الوصفية (`OfflineDownloadsDataSource` عبر `DataStore<Preferences>`) هو الزوج المُرمّز `(hex(fileId), hex(versionId))`؛ فلا تتعارض الملفات المتشابهة في العنوان العربي، وتبقى النسخ القديمة (مثل `v1`) محفوظة وقابلة للفتح دون اتصال عند نشر أو تنزيل إصدار أحدث (`v2`).
   - لا تُخزَّن أي معرّفات مستخدم (`uid`) أو بريد إلكتروني، ولا توجد مزامنة مع Firestore، ولا تُحذف الملفات عند تسجيل الخروج أو تبديل الحساب، مع بقاء استثناءات النسخ الاحتياطي ونقل الأجهزة (`allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml`) سارية.

6. **التكامل مع قارئ PDF دون المساس بالمرحلة السادسة (`PdfDocumentRepositoryImpl`):**
   - تتحقق `PdfDocumentRepositoryImpl.openDocument(fileId, activeVersionId)` أولاً من وجود نسخة محلية سليمة عبر `OfflineDownloadsRepository.getVerifiedLocalVersion(fileId, activeVersionId)` وتفتحها عبر `PdfSourceDescriptor.LocalFilePath(..., deleteOnSessionClose = false)` دون اتصال بالشبكة، مع الحفاظ الكامل على سلوك البث المباشر عند عدم توفرها محلياً وعلى تقدم القراءة المحلي (`ReadingProgressDataSource`).

---

## 2. قائمة الملفات المضافة والمعدّلة في المرحلة السابعة

### ملفات الإنتاج والتكوين الجديدة:
- `android/app/src/main/java/com/studentlearning/platform2026/domain/model/OfflineDownloadModels.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/repository/OfflineDownloadsRepository.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/local/OfflineDownloadsDataSource.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/LocalPdfStorageManager.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/DownloadStreamFetcher.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/DownloadExecutionEngine.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/DownloadTaskScheduler.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/PdfDownloadWorker.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/download/HaqibatiWorkerFactory.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/repository/OfflineDownloadsRepositoryImpl.kt`

### ملفات الإنتاج والتكوين المعدّلة:
- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/studentlearning/platform2026/HaqibatiApplication.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/core/di/AppContainer.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/repository/PdfDocumentRepositoryImpl.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesUiState.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesScreen.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/main/MainShellScreen.kt`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values-ar/strings.xml`

### ملفات الاختبارات المضافة والمعدّلة:
- `android/app/src/test/java/com/studentlearning/platform2026/fakes/FakeRepositories.kt` *(محدّث بإضافة `FakeOfflineDownloadsRepository`)*
- `android/app/src/test/java/com/studentlearning/platform2026/data/download/LocalPdfStorageManagerConcurrencyTest.kt` *(جديد — 5 اختبارات تزامن وسلامة أقفال متعددة الخيوط باستخدام `CyclicBarrier` و`CountDownLatch` تثبت إغلاق سباق TOCTOU بين `cleanupOrphanedTempFiles()` وكل من `createTempPartFile()` وإنشاء/تسجيل `.commit_*.tmp`، وسلامة التنزيلات المتزامنة، وتحرير الأقفال عند الاستثناء، وعدم تسريب المسار من `activeTempPaths` عند رمي استثناء غير `IOException` مثل `SecurityException`)*
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/OfflineDownloadsRepositoryImplTest.kt` *(20 اختبار وحدة تغطي الصلاحيات، HTTP loopback + SHA-256، رفض `X-Content-Sha256` غير الصالح، رفض 3xx Redirect، الإلغاء وتنظيف `.part`، إلغاء الاتصال البطيء المتوقف عبر `HttpServer` مع عزل نطاق DataStore وإلغائه في `finally` وتحقق إغلاق المقبس، سباقات Cancel/Start وRetry/FAILED، تداخل Cancel المتزامن مع `NonCancellable` save ومع ما قبل Commit، منع التكرار المتزامن، تلف/حذف الملف بعد الحفظ، التنظيف الآمن للملفات اليتيمة `.part`/`.commit_*.tmp`، تعدد الإصدارات، استقلال الأجهزة، تطابق العناوين، ووسيط R2 غير المهيأ)*
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/PdfDocumentRepositoryImplTest.kt` *(5 اختبارات تغطي فتح النسخة المحلية دون إنترنت، فتح `v1` و`v2` منفصلين عبر التخزين الحقيقي، وعدم المساس بتقدم القراءة)*
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModelTest.kt` *(6 اختبارات تغطي القائمة الفارغة وإعادة المحاولة، حجب الزوار، تتبع التقدم/الإلغاء/إعادة المحاولة، بقاء الإصدار القديم، قائمة القسم دون اتصال، وتحديث حالة الواجهة `uiState` بأحدث حالة فعلية عبر المستودع الحقيقي)*
- `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/SectionFilesDownloadsRtlTest.kt` *(6 اختبارات Compose RTL للواجهة والحوارات والأزرار)*
- `android/app/src/androidTest/java/com/studentlearning/platform2026/data/download/WorkManagerDownloadTest.kt` *(جديد — 5 اختبارات `WorkManagerTestInitHelper` و`TestListenableWorkerBuilder` تغطي الجدولة والإلغاء وإعادة التشغيل الفورية ومنع التكرار واستعادة الحالة بعد محاكاة إعادة الإنشاء، ونجاح الطالب في العامل، ورفض الزائر، وتسجيل الخروج قبل وأثناء التنزيل)*

---

## 3. دفعة الإصلاحات المعتمدة بعد التدقيق المستقل

1. **إغلاق سباق TOCTOU والتنظيف التشغيلي الآمن للملفات المؤقتة اليتيمة (`LocalPdfStorageManager`):**
   - تمت إضافة قفل صريح مشترك `tempLifecycleLock = ReentrantLock()` داخل `LocalPdfStorageManager` يحمي:
     - `createTempPartFile()`: التسجيل في `activeTempPaths` ثم إنشاء الملف الفارغ على القرص داخل `tempLifecycleLock.withLock` مع كتلة `finally { if (!created) activeTempPaths.remove(...) }` التي تضمن إزالة المسار عند أي فشل أو استثناء غير متوقع (بما في ذلك `SecurityException`) مع إعادة رمي الاستثناء الأصلي كما هو دون تسريبه أو ابتلاعه، وإبقاء المسار محمياً عند نجاح الإنشاء.
     - `commitVerifiedTempFile()`: عند استخدام المسار الاحتياطي `.commit_*.tmp`، يجري تسجيل المسار في `activeCommitPaths` ثم `createNewFile()` داخل `tempLifecycleLock.withLock`، ثم يُحرَّر القفل قبل نسخ البايتات (`copyTo`) كي لا تُحجب التنزيلات أو التنظيفات الأخرى، مع الإبقاء على `activeCommitPaths` طوال مدة `commit` حتى التنظيف النهائي وإزالة المسار في `finally` تحت `tempLifecycleLock.withLock`.
     - `releaseTempFile()` و`deleteTempFileQuietly()` و`cleanupOrphanedTempFiles()` و`listAllTempFiles()`: تعمل جميعها تحت `tempLifecycleLock.withLock` بحيث تصبح قراءة المسارات المحمية ومسح المجلد (`listFiles()`) وحذف الملفات اليتيمة (`.part` و`.commit_*.tmp` فقط دون المساس مطلقاً بـ `document.pdf`) عملية ذرية بالنسبة لبعضها البعض.
   - استُدعيت `cleanupOrphanedTempFiles()` على خيط `IO` من `HaqibatiApplication.onCreate()` ومن بداية `DownloadExecutionEngine.executeDownload(...)` دون حجب `Main Thread` أو استخدام `runBlocking`.

2. **إصلاح تزامن `WorkManager` وإلغاء التنزيل وإعادة المحاولة (`WorkManagerDownloadScheduler` و`OfflineDownloadsRepositoryImpl`):**
   - تعليق غير حاجز (`suspendCancellableCoroutine` + `ListenableFuture.addListener`) على `enqueueUniqueWork(...).result` و`cancelUniqueWork(...).result` و`getWorkInfosForUniqueWork(...)` بدل الإطلاق دون انتظار أو `.get()` الحاجز.
   - إضافة `taskId` في `DownloadTaskState` وربط `PendingTaskHint` بـ `supersededScheduledTaskId` / `baselineScheduledState` بحيث:
     - لا تخفي حالة `FAILED` القديمة حالة `QUEUED` الجديدة عقب `retryDownload`.
     - لا يمنع `WorkInfo` قديم ما زال يُصفّى بعد `cancelDownload` بدءَ تنزيل جديد فوري لنفس `(fileId, versionId)`.
     - إذا اكتمل الحفظ (`commit` + `DataStore`) قبل أو أثناء وصول الإلغاء، لا يُحذف الملف المكتمل ولا يُطمس مؤشر `COMPLETED` بـ `CANCELLED`.

3. **تشديد التحقق من ترويسة `X-Content-Sha256` وتسجيل الخروج أثناء التنزيل:**
   - يرفض `HttpDownloadStreamFetcher` أي قيمة غير فارغة في `X-Content-Sha256` لا تطابق 64 خانة Hex برمز `DownloadFailureReason.INTEGRITY_CHECK_FAILED` بدلاً من تجاهلها، بينما يستمر الغياب أو القيمة الفارغة بالاعتماد على `expectedChecksumSha256` إن وُجد.
   - يراقب `PdfDownloadWorker` تغير الجلسة (`authRepository.sessionFlow`) ويعيد فحص الأهلية قبل التثبيت (`commitVerifiedTempFile`)؛ فإذا سُجّل الخروج أثناء التنزيل ينقطع التحويل ويُحذف `.part` مع إبقاء النسخ المكتملة سابقاً على الجهاز سليمة.

4. **سياسة إعادة التوجيه (HTTP Redirect Policy):**
   - أُبقي `instanceFollowRedirects = false` في `HttpDownloadStreamFetcher` لمنع اتباع أي 3xx نحو نطاقات غير موثوقة أو الخفض إلى `http://`.
   - تم توثيق التوقّع المعماري صراحةً في `HttpDownloadStreamFetcher` و`BrokeredPdfFileSourceDataSource`: يجب أن يبث وسيط Cloudflare R2 المستقبلي محتوى PDF مباشرة عبر HTTPS باستجابة `HTTP 200 OK` (أو يُرجع رابطاً مباشراً عبر استجابة JSON للوسيط قبل الجلب).

5. **تقييم التنزيلات الطويلة (`ForegroundInfo` وHTTP `Range`):**
   - **الوضع الحالي الأنسب:** آلية `WorkManager` القياسية الحالية (`OneTimeWorkRequest` بمهلة تنفيذ حتى ~10 دقائق لكل محاولة) ملائمة لأحجام ملفات الدراسة والملخصات المعتادة دون إضافة أذونات `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / إشعارات دائمة (التي يفرضها `targetSdk = 35`) دون حاجة مثبتة.
   - **متى يلزم التطوير مستقبلاً:** إذا أظهر قياس الملفات الحقيقية بعد تفعيل R2 وجود كتب كبيرة تتجاوز نافذة 10 دقائق أو تتأثر بانقطاعات متكررة، يُوصى حينها بإضافة `ForegroundInfo` (مع إذن `FOREGROUND_SERVICE_DATA_SYNC`) ودعم استئناف `HTTP Range` مشروطاً بدعم الخادم (`Accept-Ranges: bytes` + `ETag` ثابت لكل `versionId`).

---

## 4. حالة التحقق الفعلي مقابل ما هو مكتوب فقط

- **بيئة التنفيذ الحالية:** حاوية Linux بدون `JDK` وبدون `Android SDK` وبدون محاكي أو جهاز Android، ولا يوجد ربط فعلي بـ Cloudflare R2 (`brokerEndpointBaseUrl = null`) التزاماً بقيود الأمان وعدم استخدام روابط أو ملفات وهمية.
- **ما تم التحقق منه:** مراجعة الشيفرة وبنية التزامن والأقفال وتطابق الاستيرادات (`androidx.work:work-testing:2.10.0`، `kotlinx-coroutines-test`) وتكامل الطبقات والموارد النصية العربية (`values/strings.xml` و`values-ar/strings.xml`).
- **ما لم يُنفذ بعد (مكتوب فقط):** تجميع Kotlin/Gradle (`assembleDebug`، `assembleRelease`، `lintDebug`) وتشغيل اختبارات الوحدة (`testDebugUnitTest` ومنها `LocalPdfStorageManagerConcurrencyTest` و`OfflineDownloadsRepositoryImplTest`) واختبارات الأجهزة (`connectedDebugAndroidTest`). كما أن اختبارات `WorkManagerTestInitHelper` تحاكي جدولة `WorkManager` وإعادة إنشاء المستودع برمجياً ولا تغني عن الاختبار الحقيقي لقتل العملية وإعادة تشغيل الجهاز على هاتف أو محاكي Android فعلي.

---

## 5. أوامر البناء والاختبار المطلوب تشغيلها في بيئة Android Studio / JDK 17 + SDK 35

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

*(لتفعيل التنزيلات من الخادم لاحقاً بعد المرحلة الثامنة/التاسعة: يلزم توفير رابط وسيط التوقيع الآمن لـ Cloudflare R2 في `BrokeredPdfFileSourceDataSource` وفق سياسة البث المباشر عبر HTTPS دون إعادة توجيه 3xx.)*
