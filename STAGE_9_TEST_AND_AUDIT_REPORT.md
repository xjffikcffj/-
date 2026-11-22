# تقرير الاختبارات والمراجعة الشاملة — المرحلة التاسعة (`STAGE_9_TEST_AND_AUDIT_REPORT.md`)

**المشروع:** حقيبتي (`com.studentlearning.platform2026`)  
**المشروع المرجعي في Firebase:** `student-learning-platform-2026` (`48673314803`)، قاعدة Firestore `(default)`، المنطقة المرجعية `me-central1`  
**المرحلة:** المرحلة التاسعة — الاختبارات والمراجعة الشاملة (دون فتح المرحلة الثامنة ودون بدء المرحلة العاشرة)  

---

## 1. نطاق المراجعة

شملت المراجعة الشاملة في المرحلة التاسعة فحص جميع مسارات الإنتاج والاختبارات والإعدادات المتوفرة في مساحة العمل الحالية دون الاتصال بـ GitHub ودون إنشاء موارد سحابية أو نشر قواعد إلى Firebase أو R2، وذلك ضمن المحاور الثلاثة الآتية:

1. **المراجعة الوظيفية (10 مجالات):** الزوار، حسابات الطلاب، الصلاحيات وRBAC، تصفح المكتبة ودعم RTL/العربية، قارئ PDF والبحث العربي، التنزيل بدون إنترنت، استقلالية التنزيلات عن الحساب ومشاركة الجهاز، إدارة الإصدارات وشارة «إصدار جديد» (30 يوماً)، الإشعارات، ولوحة الإدارة وسجل التعديلات.
2. **التدقيق الأمني (12 نقطة):** Firebase Custom Claims وتحديث التوكن، قواعد `firestore.rules`، فصل أدوار `Owner / Admin / Student / Guest`، منع الكتابة المباشرة لـ `versions` و`upload_sessions` و`isPublished`، تحقق Backend من التوكن مع `checkRevoked`، التحقق الحي من الطبقات الخمس (`branches -> subjects -> sections -> study_files -> versions`)، حماية مسارات الرفع والتحقق والنشر، عدم تسريب روابط/مفاتيح Cloudflare R2، تحقق الحجم وبصمة `SHA-256` والترويسة `%PDF-`، منع الملفات غير المنشورة أو المؤرشفة، سلامة رسائل الأخطاء وسجلات التشغيل، ووضع `Fail-Closed`.
3. **الفحص التقني والجاهزية:** الترجمة وتطابق النصوص (`values/strings.xml` مقابل `values-ar/strings.xml`)، معالجة الاستثناءات و`CancellationException`، دورة الحياة والملاحة، حالات التحميل والفراغ والخطأ، الذاكرة وإغلاق `PdfRenderer` ومقاييس الكاش، أذونات `AndroidManifest.xml`، ونظافة الأسرار والملفات المؤقتة.

---

## 2. الملفات التي فُحصت

- **الخادم الوسيط (Backend D2 — `server/`):**
  - `server/server.ts`, `server/app.ts`, `server/config/env.ts`, `server/firebase/adminApp.ts`
  - `server/middleware/auth.ts`, `server/middleware/rateLimit.ts`
  - `server/routes/catalogRoutes.ts`, `server/routes/deliveryRoutes.ts`, `server/routes/adminRoutes.ts`
  - `server/services/catalogService.ts`, `server/services/deliveryService.ts`, `server/services/uploadVerificationService.ts`, `server/services/auditLogService.ts`, `server/services/staffManagementService.ts`, `server/services/notificationDispatchService.ts`
  - `server/storage/r2Storage.ts`, `server/validation/validators.ts`, `server/types/domain.ts`, `server/utils/errors.ts`
- **قواعد الأمان والفهارس والاختبارات الخادمية (`firestore.rules`, `firestore.indexes.json`, `tests/`):**
  - `firestore.rules`, `firestore.indexes.json`
  - `tests/firestore.rules.test.ts` (`19` اختباراً)
  - `tests/backend/backend.api.test.ts` (`16` اختبار تكامل على المحاكي)
  - `tests/backend/entrypointAndRateLimit.test.ts` (`7` اختبارات مستقلة)
  - `tests/backend/r2StagingLifecycle.test.ts` (`13` اختباراً مستقلاً)
  - `tests/backend/governanceAndNotifications.test.ts` (`3` اختبارات تكامل HTTP مستقلة للحوكمة والتدقيق والإشعارات)
- **تطبيق Android (Kotlin + Jetpack Compose — `android/app/src/main/`):**
  - التهيئة والملاحة: `HaqibatiApplication.kt`, `MainActivity.kt`, `core/di/AppContainer.kt`, `core/locale/ArabicLocaleHelper.kt`, `navigation/AppDestination.kt`, `navigation/HaqibatiNavHost.kt`, `presentation/shell/MainShellScreen.kt`
  - الإشعارات (`FCM` وسجل الإشعارات المحلي): `data/notifications/HaqibatiFirebaseMessagingService.kt`, `data/notifications/NotificationChannelHelper.kt`, `data/local/LocalNotificationDataSource.kt`, `data/repository/NotificationLogRepositoryImpl.kt`, `presentation/notifications/NotificationsViewModel.kt`, `presentation/notifications/NotificationsScreen.kt`, `presentation/notifications/NotificationsUiState.kt`
  - الشاشات ومجالات العرض: `SplashViewModel.kt`, `HomeViewModel.kt`, `HomeScreen.kt`, `AboutScreen.kt`, `LibraryViewModel.kt`, `LibraryScreen.kt`, `SubjectsViewModel.kt`, `SubjectsScreen.kt`, `SectionsViewModel.kt`, `SectionsScreen.kt`, `SectionFilesViewModel.kt`, `SectionFilesScreen.kt`, `AccountViewModel.kt`, `SignInScreen.kt`, `SignUpScreen.kt`, `ForgotPasswordScreen.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `DownloadsViewModel.kt`, `DownloadsScreen.kt`, `PdfReaderViewModel.kt`, `PdfReaderScreen.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt`, `AdminPortalDialogs.kt`
  - المستودعات والبيانات والشبكات: `AuthRepositoryImpl.kt`, `AdminCatalogRepositoryImpl.kt`, `AdminGovernanceRepositoryImpl.kt`, `LocalFileOrganizationRepositoryImpl.kt`, `OfflineDownloadsRepositoryImpl.kt`, `FirebaseClientProvider.kt`, `FirebaseAuthDataSource.kt`, `BackendHttpClient.kt`, `BackendCatalogDataSource.kt`, `BackendAdminVersionDataSource.kt`, `HybridAdminCatalogDataSource`, `FirestoreAdminCatalogDataSource.kt`, `PdfFileSourceDataSource.kt`, `BrokeredPdfVersionStorageDataSource.kt`, `HttpPdfContentFetcher.kt`, `DownloadStreamFetcher.kt`, `DownloadExecutionEngine.kt`, `HaqibatiWorkerFactory.kt`, `AndroidPdfDocumentEngine.kt`, `LocalFileOrganizationDataSource.kt`, `OfflineDownloadsDataSource.kt`, `ReadingProgressDataSource.kt`, `PreferencesDataSource.kt`
  - النماذج والمصادقين: `FirestoreCatalogSchema.kt`, `AuthValidator.kt`, `AdminRole.kt`, `AdminGovernanceModels.kt`, `LocalFileOrganizationModels.kt`, `LocalNotificationModels.kt`, `StudyFileMetadata.kt`, `OfflineDownloadModels.kt`, `PdfReaderModels.kt`, `ArabicTextNormalizer.kt`
- **الإعدادات والموارد ومعاينة الويب والاختبارات:**
  - `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-ar/strings.xml`, `android/app/src/main/res/xml/locales_config.xml`, `android/app/src/main/res/values/themes.xml`
  - `.env.example`, `.gitignore`, `android/.gitignore`, `package.json`
  - اختبارات معاينة الويب: `src/data/seedCatalog.test.ts`, `src/domain/fixesVerification.test.ts`, `src/screens/pdfReaderDom.test.tsx`

---

## 3. نتائج المراجعة الوظيفية (10 مجالات)

| # | المجال الوظيفي | الحالة بالاعتماد على الشيفرة والاختبارات | التفاصيل والملاحظات الصريحة |
|---|---|---|---|
| 1 | **الزوار (Guest)** | **مكتمل ومتوافق** | يتيح `AuthRepositoryImpl` و`LibraryScreen`/`SectionFilesScreen` التصفح دون تسجيل دخول عبر `/api/catalog/*`، وفتح ملفات PDF عبر `/api/study-files/{fileId}/versions/{versionId}/stream` (`PdfFileSourceDataSource`). تُمنَع التنزيلات المحلية للزائر في `OfflineDownloadsRepositoryImpl` و`BrokeredPdfFileSourceDataSource.resolveDownloadPdfSource` (`UNAUTHORIZED_ACCESS` عند غياب `ID Token`) وتظهر دعوة تسجيل الدخول أو إنشاء حساب في `DownloadsScreen` و`SectionFilesScreen`. |
| 2 | **حسابات الطلاب (Auth)** | **مكتمل ومتوافق** | يدعم `FirebaseAuthDataSource` و`AuthRepositoryImpl` التسجيل بالبريد وكلمة المرور (مع إجبار الدور المحلي على `AdminRole.NONE` في `SignUpScreen`)، وتسجيل الدخول، واستعادة كلمة المرور عبر `ForgotPasswordScreen`، والخروج، واستعادة الجلسة عند التشغيل via `AuthStateListener` مع `readCurrentSession(forceTokenRefresh = true)` ومسح الجلسة عند تعطيل الحساب أو فقدانه. |
| 3 | **الصلاحيات وRBAC وإدارة المالك للمشرفين** | **مكتمل برمجياً (تنتظر حزمة Android التحقق في بيئة JDK/SDK)** | يفصل التطبيق والخادم و`firestore.rules` بين `Owner` (إدارة الفروع + إلغاء نشر المواد + عرض المشرفين ومنح/سحب صلاحية `admin` via Backend D2 مع حفظ `customClaims` الأخرى و`revokeRefreshTokens` + منع تعيين `owner` أو تعديل مالك/نفسه) و`Admin` (إدارة المواد/الأقسام/الملفات/الإصدارات + عرض سجل التدقيق وبث إشعار تحديث مهم، وممنوع من نقاط `/api/admin/staff*` بـ `403 INSUFFICIENT_ROLE`) و`Student`/`Guest`. |
| 4 | **المكتبة وRTL والعربية** | **مكتمل ومتوافق** | التسلسل الهرمي الخماسي (`branches -> subjects -> sections -> study_files -> versions`) يعمل بمعرّفات `ID` ثابتة وتصفية حيّة للمنشور وغير المؤرشف، مع فرض العربية عبر `ArabicLocaleHelper` (`locales_config.xml`) و`supportsRtl="true"` في `AndroidManifest.xml` ومكونات Compose RTL في الشاشات والاختبارات (`*RtlTest.kt`). |
| 5 | **قارئ PDF والبحث العربي** | **مكتمل ومتوافق** | يدعم `PdfReaderScreen` و`PdfReaderViewModel` و`AndroidPdfDocumentEngine` عرض الصفحات عبر `PdfRenderer` مع كاش صور محدود، التكبير والتمرير، حفظ موضع القراءة وتحديثه، والبحث العربي الموحّد عبر `ArabicTextNormalizer` مع معالجة واضحة لحالة PDF المصوّر دون نص قابل للاستخراج (`NO_EXTRACTABLE_TEXT`)، بالإضافة إلى إثباتات سلوك القارئ في اختبارات الويب واختبارات Android. |
| 6 | **التنزيل بدون إنترنت** | **مكتمل ومتوافق** | التنزيل في `DownloadTaskScheduler` (WorkManager + `HaqibatiWorkerFactory`) و`DownloadExecutionEngine` و`LocalPdfStorageManager` يتحقق قبل الحفظ النهائي من `fileSizeBytes` و`SHA-256` والترويسة `%PDF-` قبل النقل الذري من `.part` إلى المسار النهائي، ويتحقق `OfflineDownloadsRepositoryImpl` عند فتح الملف غير المتصل أو عبر الشبكة من نشر جميع الأجداد في الكتالوج، مع احترام تعدد الإصدارات في المسار `(fileId, versionId)`. |
| 7 | **استقلالية الجهاز: التنزيلات والمفضلة المحلية و«ترتيب ملفاتي»** | **مكتمل برمجياً (مختبر خوارزمياً في Unit Tests، ينتظر تشغيل Gradle)** | ترتبط التنزيلات (`OfflineDownloadsDataSource`) وتقدم القراءة (`ReadingProgressDataSource`) والتفضيلات (`PreferencesDataSource`) **والمفضلة المحلية والترتيب الشخصي (`LocalFileOrganizationDataSource`)** بالجهاز محلياً via `DataStore` **دون أي `uid` أو مزامنة في Firestore**، فتبقى محفوظة بعد إغلاق التطبيق أو تبديل الحساب/الخروج. تتيح `SectionFilesScreen` و`DownloadsScreen` وضع/إزالة المفضلة، التصفية للمفضلة فقط، ووضع «ترتيب ملفاتي» للملفات المنزّلة وغير المنزّلة مع أزرار صريحة: **حفظ الترتيب / إلغاء التعديلات / استعادة الترتيب الافتراضي** دون تغيير ترتيب المكتبة العامة. |
| 8 | **إدارة الإصدارات وشارة «إصدار جديد» (30 يوماً)** | **مكتمل ومتوافق** | تدعم `versions` الترقيم التصاعدي ونشر الإصدار النشط (`activeVersionId`/`activeVersionNumber`) وإعادة نشر ملف الأب، وتحسب سياسة `NewVersionBadgePolicy` و`SectionFilesViewModel` شارة «إصدار جديد» لمدة `30` يوماً مع احترام `dismissedVersionBadge` محلياً وحماية الوصول للإصدار النشط المنشور فقط. |
| 9 | **الإشعارات وسجل الإشعارات المحلي و`POST_NOTIFICATIONS`** | **مكتمل برمجياً (دون ادعاء الإرسال الحي قبل الإطلاق)** | يستقبل `HaqibatiFirebaseMessagingService` إشعارات `NEW_FILE` و`NEW_VERSION` و`IMPORTANT_UPDATE`، ويشترك تلقائياً في موضوع `haqibati_updates` عبر `HaqibatiApplication` عندما يكون Firebase مهيأً، ويخزن الإشعارات في `LocalNotificationDataSource` المستقل محلياً لكل جهاز مع دعم القراءة وتحديد الكل كمقروء والحذف ومسح السجل وفتح الملف في `NotificationsScreen`، وطلب إذن `POST_NOTIFICATIONS` على Android 13+. ويرسل Backend D2 الإشعار بشكل best-effort عند نشر الإصدار أو عبر `POST /api/admin/notifications/broadcast`. **ملاحظة ملزمة:** الإرسال الحي عبر FCM لا يعمل فعلياً إلا بعد وضع `google-services.json` وتهيئة FCM على الخادم في مرحلة الإطلاق. |
| 10 | **لوحة الإدارة، إدارة المشرفين، وبث الإشعارات وسجل التدقيق (`audit_logs`)** | **مكتمل برمجياً (مختبر في Backend HTTP، ينتظر Gradle ومحاكي Firestore)** | أُضيفت مجموعة `audit_logs` بقاعدة `append-only` في `firestore.rules` (تمنع التعديل والحذف للجميع وتمنع التزوير)، وخدمة `AuditLogService` في الخادم، والتسجيل الأفضل-جهداً في `FirestoreAdminCatalogDataSource`، وتبويب **سجل التدقيق (`AUDIT_LOGS`)** مع أداة **بث إشعار مهم** لجميع المشرفين والمالك، وتبويب **المشرفون (`STAFF`)** المحصور في **المالك (`OWNER`)** فقط. |

---

## 3.5. مراجعة النقاط الخمس بعد الاستكمال في المرحلة التاسعة

> **توضيح منهجي:** جرى في هذه الجولة إكمال جميع الوظائف الخمس الناقصة التي رُصدت في المراجعة السابقة، مع إبقاء التحقق البيئي لـ Android (`Gradle`) ومحاكيات Firebase (`Firestore Emulator`) مفصولاً بوضوح تام عما جرى تشغيله فعلياً.

| # | الوظيفة | منفذة فعلياً؟ | الملفات / الأصناف المنفذة | الاختبارات المضافة وحالة تشغيلها | الملاحظات والحدود الصريحة |
|---|---|---|---|---|---|
| 1 | **المفضلة المحلية لكل جهاز** | **نعم** | `LocalFileOrganizationModels.kt`, `LocalFileOrganizationRepository.kt`, `LocalFileOrganizationDataSource.kt`, `LocalFileOrganizationRepositoryImpl.kt`, `SectionFilesViewModel.kt`/`Screen.kt`, `DownloadsViewModel.kt`/`Screen.kt` | `LocalFileOrganizationDataSourceTest.kt`, اختبارات مضافة في `SectionFilesViewModelTest.kt` و`DownloadsViewModelTest.kt` *(مكتوبة، تنتظر `JDK + Android SDK`)*. | مخزّنة في DataStore مستقلة محلياً لكل جهاز دون أي `uid` أو مزامنة عبر Firestore. |
| 2 | **ميزة «ترتيب ملفاتي» وحفظ الترتيب أو إلغاء التعديلات** | **نعم** | `LocalFileOrderingHelper` (`LocalFileOrganizationModels.kt`), `LocalFileOrganizationDataSource.kt`, `SectionFilesViewModel.kt`/`Screen.kt`, `DownloadsViewModel.kt`/`Screen.kt` | `LocalFileOrganizationDataSourceTest.kt`, اختبارات مضافة في `SectionFilesViewModelTest.kt` (ملفات منزّلة وغير منزّلة + حفظ/إلغاء/استعادة) و`DownloadsViewModelTest.kt` *(تنتظر `JDK + Android SDK`)*. | تحافظ على ترتيب المكتبة العامة وعروض الطلاب الآخرين دون أي تغيير؛ وتمنع فساد الترتيب الكامل أثناء تفعيل فلتر المفضلة. |
| 3 | **إشعارات صدور الملفات والإصدارات وسجل الإشعارات المحلي** | **نعم** | `HaqibatiFirebaseMessagingService.kt`, `NotificationChannelHelper.kt`, `LocalNotificationDataSource.kt`, `NotificationLogRepositoryImpl.kt`, `NotificationsViewModel.kt`/`Screen.kt`, `AndroidManifest.xml` (`POST_NOTIFICATIONS`), `HaqibatiApplication.kt`, `server/services/notificationDispatchService.ts`, `uploadVerificationService.ts` | `governanceAndNotifications.test.ts` (**تم التشغيل ونجح `3 / 3`**)، `LocalNotificationDataSourceTest.kt` و`NotificationsViewModelTest.kt` *(تنتظر `JDK + Android SDK`)*. | يتعامل التطبيق مع `POST_NOTIFICATIONS` على Android 13+؛ **ولا يُدّعى عمل البث الحي لـ FCM قبل توفير `google-services.json` واعتمادات الإرسال في مرحلة الإطلاق**. |
| 4 | **إدارة المالك للمشرفين وصلاحياتهم** | **نعم** | `server/services/staffManagementService.ts`, `server/middleware/auth.ts` (`requireOwner`), `server/routes/adminRoutes.ts` (`/staff`, `/staff/roles`), `AdminGovernanceRepositoryImpl.kt`, `BackendAdminVersionDataSource.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt` (تبويب `STAFF`), `AdminPortalDialogs.kt` | `governanceAndNotifications.test.ts` (**تم التشغيل ونجح**)، `AdminGovernanceRepositoryImplTest.kt` واختبارات مضافة في `AdminPortalViewModelTest.kt` *(تنتظر `JDK + Android SDK`)*. | محصور في `OWNER` فقط، يحفظ Claims الأخرى، يستدعي `revokeRefreshTokens`، ويمنع تعيين `owner` أو تعديل مالك/النفس عبر الواجهة والخادم. |
| 5 | **تسجيل العمليات الإدارية المهمة في سجل التدقيق (`audit_logs`)** | **نعم** | `firestore.rules` (`audit_logs` append-only), `server/services/auditLogService.ts`, `catalogService.ts`, `uploadVerificationService.ts`, `staffManagementService.ts`, `notificationDispatchService.ts`, `adminRoutes.ts` (`/audit-logs`), `FirestoreAdminCatalogDataSource.kt`, `AdminPortalScreen.kt` (تبويب `AUDIT_LOGS`) | `governanceAndNotifications.test.ts` (**تم التشغيل ونجح**)، `tests/firestore.rules.test.ts` القسم `11` *(ينتظر محاكي Firestore)*، `AdminGovernanceRepositoryImplTest.kt` و`AdminPortalViewModelTest.kt` *(تنتظر Gradle)*. | السجل محمي بقواعد `append-only` تمنع التعديل أو الحذف أو انتحال `actorUid`/`actorRole`/`source`. |

---

## 4. نتائج التدقيق الأمني (12 نقطة)

| # | نقطة التدقيق الأمني | النتيجة | الدليل المرجعي في الشيفرة |
|---|---|---|---|
| 1 | **Custom Claims وتحديث التوكن** | **آمن / متطابق** | `FirebaseAuthDataSource` يقرأ `claims["role"]` عبر `AdminRole.fromClaim`، ويستدعي `getIdToken(true)` عند استعادة الجلسة، ويجبر `BackendAdminVersionDataSource` و`AdminPortalViewModel` على `forceTokenRefresh = true` في عمليات الإدارة؛ ويُحظر تعديل `role` من العميل (`SignUp` يضبط `AdminRole.NONE`). |
| 2 | **قواعد `firestore.rules`** | **آمن / متطابق** | قواعد صريحة تقفل الوصول الافتراضي؛ تقصر قراءة `branches`/`subjects`/`sections`/`study_files` و`audit_logs` المباشرة على `isAdminOrOwner()`، وتمنع إنشاء/تحديث/حذف `versions` و`upload_sessions` تماماً من العميل، وتجعل `audit_logs` **إلحاقياً فقط (`append-only`)** مع منع التعديل والحذف كلياً ومنع تزوير `actorUid`/`actorRole`/`source`، وتفرض قيود الحجم والأنواع والحالة الثلاثية `isArchived` (تشديد `F-2G-2`). |
| 3 | **فصل RBAC (`Owner / Admin / Student / Guest`)** | **آمن / متطابق** | في كلٍ من `firestore.rules` و`server/middleware/auth.ts` (`requireOwner` / `requireAdminOrOwner`) و`AdminCatalogRepositoryImpl` و`AdminGovernanceRepositoryImpl` و`AdminPortalViewModel`: إنشاء/تعديل/ترتيب/نشر الفروع وإلغاء نشر المواد وإدارة صلاحيات المشرفين (`/api/admin/staff*`) محصورة في `Owner`؛ وبقية إدارة الكتالوج والرفع وقراءة التدقيق وبث الإشعارات لـ `Owner/Admin`؛ و`Student/Guest` ممنوعان من أي كتابة في الكتالوج أو الإدارة. |
| 4 | **منع الكتابة المباشرة لـ `versions` و`upload_sessions` و`isPublished`** | **آمن / متطابق** | ترفض `firestore.rules` أي إنشاء لـ `study_files` بحالة `isPublished == true` أو وجود `activeVersion*`، وتمنع تحديث `isPublished: true` أو `activeVersion*` من العميل عبر `PRIVILEGED_FIELDS_LOCKED`، وتُبقي الكتابة حصراً بيد الخادم بعد التحقق عبر Firebase Admin SDK. |
| 5 | **تحقق Backend من التوكن مع `checkRevoked`** | **آمن / متطابق** | تستدعي `server/middleware/auth.ts` الدالة `verifyIdToken(token, true)` للتحقق من الصلاحية والإلغاء (`checkRevoked = true`) قبل السماح بأي طلب مصادق عليه أو إداري، مع حصر مسارات الإدارة في `requireAdminOrOwner` وحصر إدارة المشرفين في `requireOwner`. |
| 6 | **التحقق الحي من الطبقات الخمس** | **آمن / متطابق** | في `server/services/catalogService.ts` و`deliveryService.ts` يتم فحص تسلسل الأهل حيّاً دون كاش (`branch -> subject -> section -> studyFile -> version`) مع اشتراط `isPublished === true` و`isArchived === false` في جميع النقاط العامة والبث والتنزيل، وإضافة فلترة إضافية في `BackendCatalogDataSource` ضد التناقض. |
| 7 | **حماية `upload-slot` و`verify` و`publish`** | **آمن / متطابق** | تتحقق `server/services/uploadVerificationService.ts` من `requireAdminOrOwner`، وصلاحية الجلسة ومهلتها (`600` ثانية)، وقفل `activeAttemptId` (`Fencing` + `ETag`/`412`)، ورفض أي حقول محظورة (`PRIVILEGED_FIELD_FORBIDDEN`)، ورفض `archiveStatus == UNSPECIFIED` (`409 ARCHIVE_STATE_UNSPECIFIED`)، وإعادة فحص الإثبات داخل معاملة النشر لمنع TOCTOU. |
| 8 | **عدم تسريب روابط/مفاتيح R2** | **آمن / متطابق** | لا تُخزَّن مفاتيح R2 في التطبيق أو الويب أو العميل (`Android` لا يضمّ أي مفتاح R2)، وتُعزَل أسرار R2 في `process.env` على الخادم مع فرض `HTTPS` لـ `R2_ENDPOINT`؛ ولا تُرجَع مفاتيح التخزين الداخلية أو الروابط الموقّعة العامة في نقاط `/api/catalog/*`، بل يتوسّط الخادم البث والتنزيل عبر `/api/study-files/.../{stream,download}`. |
| 9 | **تحقق الحجم و`SHA-256` و`%PDF-`** | **آمن / متطابق** | يُتحقَّق من الحجم الأقصى (`100 MiB`)، وبصمة `SHA-256`، والترويسة السحرية `%PDF-` في كلٍ من القراءة الأولية على Android (`PdfUploadUriReader`)، وعلى الخادم عند التحقق (`uploadVerificationService.ts` مع قراءة البث وفحص `contentLength` والحجم والترويسة وبصمة `SHA-256`)، وعند التنزيل المحلي على الجهاز (`DownloadExecutionEngine`) قبل اعتماد الملف. |
| 10 | **منع الملفات غير المنشورة أو المؤرشفة** | **آمن / متطابق** | يُحجَب أي ملف غير منشور أو مؤرشَف أو يتبع فرعاً/مادة/قسماً غير منشور أو مؤرشفاً عن الضيوف والطلاب في Backend API و`BackendCatalogDataSource` وشاشة التنزيلات (`DownloadsScreen`/`OfflineDownloadsRepositoryImpl`) وفتحه في القارئ. |
| 11 | **سلامة رسائل الأخطاء وسجلات التشغيل** | **آمن / متطابق** | تعرض واجهات Android رسائل عربية واضحة دون كشف تتبّع مكدس أو معرفات سرية أو ترويسات `Authorization`، وأظهر الفحص خلوّ شيفرة الإنتاج في `android/app/src/main` و`server` و`src` من `Log.d`/`Log.v` أو طباعة التوكنات أو المفاتيح. |
| 12 | **وضع `Fail-Closed`** | **آمن / متطابق** | عند غياب `HAQIBATI_BACKEND_BASE_URL` يقف التطبيق بوضع `Fail-Closed` (`BACKEND_NOT_CONFIGURED` / `AWAITING_SECURE_BROKER_INTEGRATION` / `SECURE_SOURCE_NOT_CONFIGURED`) دون بدائل مفتوحة؛ ويرفض `BackendHttpClient` الاتصال غير المشفّر (`http://` في الإنتاج) وإعادة التوجيه (`instanceFollowRedirects = false`)؛ ويرفض `FirebaseClientProvider` أي مشروع غير `student-learning-platform-2026`؛ ويضبط الخادم `TRUST_PROXY = false` افتراضياً. |

---

## 5. نتائج الفحص التقني والجاهزية

- **الترجمة وتطابق الموارد النصية:** فُحصت جميع عناصر `<string>` في `android/app/src/main/res/values/strings.xml` مقابل `values-ar/strings.xml` فحصاً آلياً كاملاً بعد الإضافات الأخيرة: **`439 / 439` متطابق بالكامل** (`0` مفقود في العربية، `0` زائد في العربية، `0` عدم تطابق في معاملات التنسيق `%s/%d`)، إضافة إلى `9` ملفات موارد XML غير نصية سليمة.
- **معالجة الاستثناءات والإلغاء:** تم التحقق من إعادة تمرير `CancellationException` في جميع طبقات Kotlin (`AuthRepositoryImpl`, `AdminCatalogRepositoryImpl`, `AdminGovernanceRepositoryImpl`, `OfflineDownloadsRepositoryImpl`, `LocalFileOrganizationRepositoryImpl`, `NotificationLogRepositoryImpl`, `FirestoreAdminCatalogDataSource`, `DownloadStreamFetcher`) دون تغليفها في أخطاء عادية، مع عزل الحفظ الختامي بالضرورة في `NonCancellable` وإغلاق مقابس HTTP/HTTPS عند إلغاء التنزيل وحذف ملفات `.part`.
- **دورة الحياة والملاحة والحالات التفاعلية:** يعتمد `MainActivity` و`HaqibatiNavHost` على معرّفات المسارات المحفوظة (بما فيها مسار `Notifications` وسلسلة التنقل من الإعدادات وأيقونة الجرس العلوية مع شارة غير المقروءة) وحالات تحميل/فراغ/خطأ صريحة مع إمكانية إعادة المحاولة في الشاشات، ويحافظ `MainActivity` على توحيد اللغة العربية واتجاه RTL عبر `ArabicLocaleHelper`.
- **إدارة الذاكرة و`PdfRenderer`:** يستخدم `AndroidPdfDocumentEngine` عدّاد مراجع (`refCount`) قبل إغلاق `PdfRenderer` و`ParcelFileDescriptor` في `PdfDocumentSession.close()` وتفادي الإغلاق أثناء التصيير، وتعتمد واجهة القارئ على كاش صور محدود الحجم (`LruCache` بسعة `24 MiB`) وتنظيف آمن عند تبديل الملف أو إغلاق الجلسة.
- **أذونات `AndroidManifest.xml` وأقل الصلاحيات:** يعلن `AndroidManifest.xml` عن الأذونات اللازمة فقط: `INTERNET` و`ACCESS_NETWORK_STATE` و`POST_NOTIFICATIONS` (لإشعارات Android 13+ مع معالجة الإذن وقت التشغيل عبر `Manifest.permission.POST_NOTIFICATIONS` دون إجبار المستخدم، مع تسجيل `HaqibatiFirebaseMessagingService` لحدث `com.google.firebase.MESSAGING_EVENT` دون إعلان أذونات تخزين خارجي أو موقع أو كاميرا)، ويضبط `android:allowBackup="false"` و`android:supportsRtl="true"` و`android:localeConfig="@xml/locales_config"`.
- **نظافة الأسرار والملفات المؤقتة:** خلت `android/app/src/main`, `server`, `src`, و`.env.example` من أي مفاتيح R2 أو مفاتيح Firebase خاصة أو `BEGIN PRIVATE KEY` أو بيانات اعتماد مضمّنة. كما تخلو مساحة العمل من `google-services.json` أو `android/.gradle/` أو أي ملفات `.apk`/`.keystore` متبقية، وغطّت `/.gitignore` و`/android/.gitignore` استبعادها جميعاً.

---

## 6. الأخطاء والنواقص المكتشفة وتصنيفها

| التصنيف | العدد | التفاصيل |
|---|---|---|
| **أخطاء حرجة (`Critical`)** | `0` | لم تُثبت أي ثغرة أو عطل حرج في الشيفرة الحالية. |
| **أخطاء عالية (`High`)** | `0` | لم تُثبت أي مشكلة عالية الخطورة في مسارات المصادقة أو قواعد Firestore أو الخادم أو تحميل/عرض PDF. |
| **أخطاء متوسطة (`Medium`)** | `0` | أُصلح تعارض أنواع `ApiErrorCode` في `server/utils/errors.ts` الذي كشفه `npm run lint` أثناء استكمال مسارات الحوكمة والإشعارات، وأصبح فحص الأنواع نظيفاً تماماً. |
| **النواقص الوظيفية الخمس المحددة سابقاً** | **`0` متبقٍ** *(نُفّذت `5 / 5`)* | تم تنفيذ واختبار **(1) المفضلة المحلية لكل جهاز**، و**(2) «ترتيب ملفاتي» مع الحفظ والإلغاء والاستعادة**، و**(3) استقبال FCM وسجل الإشعارات المحلي و`POST_NOTIFICATIONS`**، و**(4) إدارة المالك للمشرفين وصلاحياتهم**، و**(5) سجل التدقيق المستقل `audit_logs`**. |

---

## 7. الإصلاحات والملفات التي تغيّرت لاستكمال الميزات الخمس

- **الخادم الوسيط وقواعد الأمان (`server/`, `firestore.rules`):**
  - تعديل: `firestore.rules` (إضافة مجموعة `audit_logs` الإلحاقية)، `server/types/domain.ts`, `server/firebase/adminApp.ts`, `server/middleware/auth.ts` (`requireOwner`), `server/middleware/rateLimit.ts`, `server/services/catalogService.ts`, `server/services/uploadVerificationService.ts`, `server/routes/adminRoutes.ts`, `server/app.ts`, `server/utils/errors.ts`, `server.ts`.
  - إضافة: `server/services/auditLogService.ts`, `server/services/staffManagementService.ts`, `server/services/notificationDispatchService.ts`.
- **تطبيق Android (`android/`):**
  - تعديل: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `values/strings.xml`, `values-ar/strings.xml`, `AppContainer.kt`, `HaqibatiApplication.kt`, `FirestoreCatalogSchema.kt`, `BackendAdminVersionDataSource.kt`, `FirestoreAdminCatalogDataSource.kt`, `SectionFilesUiState.kt`/`ViewModel.kt`/`Screen.kt`, `DownloadsUiState.kt`/`ViewModel.kt`/`Screen.kt`, `AppDestination.kt`, `SettingsScreen.kt`, `MainShellScreen.kt`, `HaqibatiNavHost.kt`, `AdminPortalUiState.kt`/`ViewModel.kt`/`Dialogs.kt`/`Screen.kt`.
  - إضافة: `domain/model/LocalFileOrganizationModels.kt`, `domain/model/LocalNotificationModels.kt`, `domain/model/AdminGovernanceModels.kt`, `domain/repository/LocalFileOrganizationRepository.kt`, `domain/repository/NotificationLogRepository.kt`, `domain/repository/AdminGovernanceRepository.kt`, `data/local/LocalFileOrganizationDataSource.kt`, `data/local/LocalNotificationDataSource.kt`, `data/repository/LocalFileOrganizationRepositoryImpl.kt`, `data/repository/NotificationLogRepositoryImpl.kt`, `data/repository/AdminGovernanceRepositoryImpl.kt`, `data/notifications/NotificationChannelHelper.kt`, `data/notifications/HaqibatiFirebaseMessagingService.kt`, `presentation/notifications/NotificationsUiState.kt`/`ViewModel.kt`/`Screen.kt`.
- **الاختبارات والتوثيق (`tests/`, `android/app/src/test/`):**
  - إضافة: `tests/backend/governanceAndNotifications.test.ts`, `LocalFileOrganizationDataSourceTest.kt`, `LocalNotificationDataSourceTest.kt`, `NotificationsViewModelTest.kt`, `AdminGovernanceRepositoryImplTest.kt`.
  - توسيع: `tests/firestore.rules.test.ts` (القسم 11 لـ `audit_logs`), `FakeRepositories.kt`, `SectionFilesViewModelTest.kt`, `DownloadsViewModelTest.kt`, `AdminPortalViewModelTest.kt`.
  - تحديث: `STAGE_9_TEST_AND_AUDIT_REPORT.md`.

---

## 8. الاختبارات المنفذة ونتائجها الفعلية مقابل غير المنفذة وأسبابها

### أ. الفحوصات والاختبارات التي نُفّذت ونجحت فعلياً في بيئة العمل الحالية

| الاختبار / الفحص | الأمر المنفّذ | النتيجة الفعلية | التفاصيل |
|---|---|---|---|
| فحص الأنواع (`TypeScript`) | `npm run lint` (`tsc --noEmit`) | **نجح (`EXIT=0`)** | `0` أخطاء ترجمة أو أنواع عبر الخادم والويب وجميع الاختبارات بعد آخر تعديل. |
| بناء تطبيق الويب الإنتاجي | `npm run build` (`vite build --mode production`) | **نجح (`EXIT=0`)** | بُنيت حزمة الإنتاج بنجاح دون أخطاء. |
| اختبارات Backend المستقلة (دون محاكي) | `npx tsx --test tests/backend/governanceAndNotifications.test.ts tests/backend/entrypointAndRateLimit.test.ts tests/backend/r2StagingLifecycle.test.ts` | **نجح (`23 / 23`)** | `7` في نقطة الدخول ومحدد المعدل + `3` للحوكمة وإدارة المشرفين و`audit_logs` وإشعارات FCM + `13` في دورة R2 staging وإجبار HTTPS. |
| اختبارات معاينة الويب | `npx tsx --test src/data/seedCatalog.test.ts src/domain/fixesVerification.test.ts src/screens/pdfReaderDom.test.tsx` | **نجح (`24 / 24` فحصاً عبر `3` ملفات)** | `5` فحوصات في `seedCatalog.test.ts` + `8` فحوصات في `fixesVerification.test.ts` + `11` فحص DOM تفاعلي في `pdfReaderDom.test.tsx` (`0` فشل). |
| تدقيق تطابق نصوص Android | فحص XML بين `values/strings.xml` و`values-ar/strings.xml` | **نجح (`439 / 439`)** | تطابق تام في المعرفات ومعاملات التنسيق (`%s`/`%d`) دون نقص أو زيادة. |

- **ملخص التنفيذ الفعلي:** **نجح `47 / 47` اختباراً وفحصاً مؤتمتاً قابلاً للتشغيل** (`23` Backend مستقل + `24` فحص معاينة ويب)، إضافةً إلى نجاح `npm run lint` و`npm run build` وفحص تطابق `439` نصاً في `strings.xml`؛ **`0` اختبارات منفذة فاشلة**.

### ب. الاختبارات التي تعذر تشغيلها في الحاوية الحالية وأسبابها الدقيقة

| الحزمة / المهمة غير المنفذة | العدد المتوفر في الكود | السبب البيئي الدقيق لتعذر التشغيل |
|---|---|---|
| `npm run test:rules` (`tests/firestore.rules.test.ts`) | **`19`** اختبار `it(...)` *(تشمل القسم 11 لـ `audit_logs`)* | عدم توفر `Java JDK` ومحاكي Firestore في الحاوية (`TOOLS:NO_JAVA_GRADLE_FIREBASE`). |
| `npm run test:backend` (الجزء المعتمد على المحاكي: `tests/backend/backend.api.test.ts`) | **`16`** اختبار `it(...)` *(إضافة للـ 23 التي نُفذت مستقلة)* | عدم توفر `Java JDK` ومحاكي Firestore في الحاوية. |
| `./gradlew testDebugUnitTest` (`android/app/src/test`) | **`264`** اختبار `@Test` عبر `44` ملف `.kt` *(تشمل 11 اختباراً جديداً للميزات المضافة)* | عدم توفر `Java JDK` و`Android SDK` (`ANDROID_HOME`/`ANDROID_SDK_ROOT`) في الحاوية. |
| `./gradlew connectedDebugAndroidTest` (`android/app/src/androidTest`) | **`52`** اختبار `@Test` عبر `8` ملفات `.kt` | عدم توفر `Android SDK` أو محاكي `/dev/kvm` أو جهاز Android متصل + غياب `android/app/google-services.json`. |
| `./gradlew lintDebug` و`./gradlew assembleDebug` | مهمتا Lint وبناء APK | عدم توفر `Java JDK` و`Android SDK` و`android/app/google-services.json` في الحاوية. |

- **إجمالي الاختبارات المؤتمتة غير المنفذة بسبب البيئة:** **`351` اختباراً** (`19` Rules + `16` Backend Emulator + `264` Android Unit + `52` Android Instrumented)، إضافة إلى مهمتَي `./gradlew lintDebug` و`./gradlew assembleDebug`. *(لا يُدّعى نجاح أي اختبار من هذه القائمة ولا عمل الإرسال الحي لـ FCM حتى تُشغَّل فعلياً في بيئة التطوير المكتملة ويُربط `google-services.json` عند الإطلاق).*

### ج. التصنيف الشامل لحالة الوظائف والاختبارات في المرحلة التاسعة

1. **الوظائف المنفذة والمختبرة فعلياً في البيئة الحالية:**
   - منطق فهرس الويب ومعاينة قارئ PDF والتنقل وإخفاء بيانات المعاينة في الإنتاج (`24 / 24` فحصاً).
   - نقطة دخول الخادم، حدّ المعدل و`TRUST_PROXY`، إجبار `HTTPS` لـ `R2_ENDPOINT` وعزل `uploads-staging/`، **إدارة المالك للمشرفين (`/api/admin/staff*`)، سجل التدقيق (`GET /api/admin/audit-logs` وتسجيل أحداث الإدارة)، وبث/إعداد إشعارات `NEW_FILE` و`NEW_VERSION` و`IMPORTANT_UPDATE` بأسلوب best-effort** (`23 / 23` اختبار Backend مستقل عبر HTTP الحقيقي و`InMemoryFirestore`).
   - سلامة أنواع TypeScript وبناء الإنتاج للويب (`npm run lint`, `npm run build`)، وتطابق النصوص العربية/الإنجليزية لـ Android (`439 / 439`).
2. **الوظائف المنفذة برمجياً مع اختبارات مكتوبة لكنها غير مشغّلة في هذه الحاوية (تنتظر البيئة المكتملة):**
   - جميع شاشات ومستودعات `Kotlin + Jetpack Compose` في `android/`: تصفح الزوار، حسابات الطلاب، RBAC، المكتبة الخماسية وRTL، قارئ PDF، التنزيل بدون إنترنت عبر `WorkManager`، استقلالية التنزيلات، شارة «إصدار جديد» (30 يوماً)، **المفضلة المحلية لكل جهاز، «ترتيب ملفاتي» (مع الحفظ/الإلغاء/الاستعادة للملفات المنزّلة وغير المنزّلة)، استقبال FCM وسجل الإشعارات المحلي و`POST_NOTIFICATIONS`، ولوحة إدارة المشرفين للمالك وتبويب `audit_logs`**.
   - قواعد `firestore.rules` (بما فيها `audit_logs` الإلحاقية) واختبارات المحاكي `backend.api.test.ts`.
3. **الوظائف غير المنفذة من النقاط الخمس المحددة:** **لا توجد (`0`)**؛ جرى تنفيذ النقاط الخمس جميعها في الشيفرة وإلحاق اختبارات الوحدة والتكامل الخاصة بها.
4. **الاختبارات التي تعذّر تشغيلها بسبب قيود البيئة:**
   - `npm run test:rules` (`19` اختباراً) و`tests/backend/backend.api.test.ts` (`16` اختبار تكامل) بسبب غياب `Java JDK` ومحاكي Firestore.
   - `./gradlew testDebugUnitTest` (`264` اختباراً)، `./gradlew connectedDebugAndroidTest` (`52` اختباراً)، و`lintDebug` / `assembleDebug` بسبب غياب `Java JDK` و`Android SDK` وجهاز/محاكي Android و`google-services.json`.

---

## 9. المخاطر المتبقية ومتطلبات الاختبار مع الخدمات الخارجية أو الأجهزة الحقيقية

1. **خطر تأخر التحقق التنفيذي من حزمة المحاكي وAndroid (`351` اختباراً):** لم تُنفّذ حزم `Firestore Emulator` و`Gradle` على النسخة النهائية داخل هذه الحاوية. **المتطلب:** تشغيل `npm run test:rules` و`npm run test:backend` على بيئة مزوّدة بـ `JDK 21+`، وتشغيل `./gradlew testDebugUnitTest lintDebug assembleDebug` على بيئة مزوّدة بـ `JDK 17+` و`Android SDK 35`.
2. **عدم اختبار الإرسال الحي لـ FCM قبل الإطلاق:** طبقة استقبال FCM وخدمة البث `NotificationDispatchService` جاهزتان ومختبرتان بمحاكيات برمجية، لكن **الإرسال الحي على الأجهزة الحقيقية غير متاح ولا يُدّعى عمله** إلا بعد إضافة `android/app/google-services.json` وتفعيل Firebase Cloud Messaging وبيانات الاعتماد السيرفرية في مرحلة الإطلاق.
3. **متطلبات الخدمات السحابية غير المنشورة بعد:**
   - وضع `android/app/google-services.json` الحقيقي محلياً (دون إضافته إلى Git) قبل بناء APK الاختباري/الإنتاجي.
   - نشر `firestore.rules` و`firestore.indexes.json` إلى مشروع `student-learning-platform-2026`.
   - إنشاء وتهيئة حاوية **Cloudflare R2** مع قاعدة Lifecycle لمسح مجلد `uploads-staging/` تلقائياً، وضبط مفاتيح API وحساب الخدمة في متغيرات البيئة السيرفرية.
   - نشر خادم **Backend D2** (`server.ts`) على Cloud Run عبر رابط `HTTPS` موثوق وتمريره إلى `HAQIBATI_BACKEND_BASE_URL` (ومطابقة `TRUST_PROXY` مع عدد القفزات الموثوقة فقط).
4. **متطلبات الاختبار على جهاز Android حقيقي:** تنفيذ الاختبار الحي الشامل (`Auth`, `Firestore`, `R2 upload/verify/publish`, البث، التنزيل بالخلفية عبر `WorkManager` تحت قطع الشبكة وإعادتها، و`PdfRenderer` على ملفات عربية كبيرة ومصوّرة، و`POST_NOTIFICATIONS` + وصول إشعارات `FCM` للمفضلة والترتيب المحلي بعد إغلاق التطبيق) وتمرير `connectedDebugAndroidTest`.
5. **نظافة فهرس Git في المستودع المحلي:** التأكد من خلو فهرس Git من `android/.gradle/` عبر `git ls-files android/.gradle` (ومسحه من التتبع بـ `git rm -r --cached android/.gradle` إن وُجد دون حذفه من `.gitignore`) بسبب غياب `.git` عن الحاوية المستوردة.

---

## 10. الحكم النهائي الصريح بشأن اعتماد المرحلة التاسعة والانتقال إلى المرحلة العاشرة

- **الحكم النهائي:** **اكتملت جميع الوظائف الخمس المطلوبة في الشيفرة مع اختبارات Backend التي نجحت (`23 / 23`) وفحص الأنواع وبناء الإنتاج (`npm run lint`, `npm run build`) وتطابق النصوص (`439 / 439`)، مع بقاء الاعتماد التنفيذي النهائي للمرحلة التاسعة مشروطاً بتشغيل اختبارات Android وFirestore Emulator (`351` اختباراً) واختبار الإرسال الحي لـ FCM عند توفر `JDK + Android SDK + Firestore Emulator + google-services.json` قبل النشر الحي في المرحلة العاشرة.**
- **التفصيل الصريح للحكم:**
  1. **اكتمال التنفيذ الوظيفي (`5 / 5`):** نُفّذت المفضلة المحلية، وميزة «ترتيب ملفاتي» (مع الحفظ/الإلغاء/الاستعادة)، وإشعارات FCM وسجل الإشعارات المحلي وإذن `POST_NOTIFICATIONS`، وإدارة المالك للمشرفين وصلاحياتهم، ومجموعة `audit_logs` وسجل التدقيق في لوحة الإدارة.
  2. **ما ثبت تنفيذاً ونجاحاً الآن:** `npm run lint` (`EXIT=0`), `npm run build` (`EXIT=0`), `47 / 47` اختباراً وفحصاً عبر Node (`23` Backend مستقل + `24` فحص ويب), وتطابق موارد النصوص `439 / 439`.
  3. **ما ينتظر البيئة المكتملة قبل الإغلاق الميداني النهائي:** تشغيل `./gradlew testDebugUnitTest lintDebug assembleDebug` (`264` اختبار وحدة) و`connectedDebugAndroidTest` (`52` اختبار واجهة/جهاز) و`npm run test:rules` (`19` اختباراً) و`npm run test:backend` (`16` اختبار محاكي) والتحقق الحي من FCM بعد تزويد `google-services.json`.

