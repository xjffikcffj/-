# تقرير الاختبارات والمراجعة الشاملة — المرحلة التاسعة (`STAGE_9_TEST_AND_AUDIT_REPORT.md`)

**المشروع:** حقيبتي (`com.studentlearning.platform2026`)  
**المشروع المرجعي في Firebase:** `student-learning-platform-2026` (`48673314803`)، قاعدة Firestore `(default)`، المنطقة المرجعية `me-central1`  
**المرحلة:** المرحلة التاسعة — الاختبارات والمراجعة الشاملة (دون فتح المرحلة الثامنة، ودون بدء المرحلة العاشرة، ودون أي نشر سحابي أو دفع Git)

---

## 1. نطاق المراجعة وبيئة التحقق المنفّذة

شملت الجولة النهائية للمرحلة التاسعة تجهيز بيئة الاختبار محلياً داخل بيئة العمل (دون إنشاء موارد مدفوعة أو حسابات خارجية ودون نشر إلى الإنتاج)، وتنفيذ مجموعة الاختبارات والفحوصات الآلية الفعلية، وإصلاح الأخطاء الفعلية التي ظهرت في اختبارات وحدة Android، وإعادة التحقق منها:

- **بيئة الاختبار المحلية المجهّزة والمستخدمة فعلياً:**
  - `Node.js v22.23.2` و`npm 10.9.8`.
  - `OpenJDK 17` (`/usr/lib/jvm/java-17-openjdk-amd64`) لبناء واختبار Android مع Gradle Wrapper (`Gradle 8.11.1`).
  - `OpenJDK 21` (`/usr/lib/jvm/java-21-openjdk-amd64`) لتشغيل محاكي Firebase Firestore.
  - `Android SDK` (`platforms;android-35` و`build-tools` `35.0.0` / `34.0.0` و`platform-tools`) في `/opt/android-sdk`.
  - `Firebase CLI 15.34.1` مع `Firestore Emulator` الحقيقي.
- **المحاور الثلاثة للمراجعة:**
  1. **المراجعة الوظيفية (10 مجالات):** الزوار، حسابات الطلاب، الصلاحيات وRBAC، تصفح المكتبة ودعم RTL/العربية، قارئ PDF والبحث العربي، التنزيل بدون إنترنت، استقلالية التنزيلات والمفضلة والترتيب عن الحساب ومشاركة الجهاز، إدارة الإصدارات وشارة «إصدار جديد» (30 يوماً)، الإشعارات وسجلها المحلي، ولوحة الإدارة وإدارة المشرفين وسجل التدقيق.
  2. **التدقيق الأمني (12 نقطة):** Custom Claims وتحديث التوكن، قواعد `firestore.rules` (بما فيها حماية `audit_logs` من الكتابة عبر العميل وقصر قراءتها على المالك)، فصل أدوار `Owner / Admin / Student / Guest`، منع الكتابة المباشرة لـ `versions` و`upload_sessions` و`isPublished`، تحقق Backend من التوكن مع `checkRevoked`، التحقق الحي من الطبقات الخمس (`branches -> subjects -> sections -> study_files -> versions`)، حماية مسارات الرفع والتحقق والنشر، عدم تسريب روابط/مفاتيح Cloudflare R2، تحقق الحجم وبصمة `SHA-256` والترويسة `%PDF-`، منع الملفات غير المنشورة أو المؤرشفة، سلامة رسائل الأخطاء وسجلات التشغيل، ووضع `Fail-Closed`.
  3. **الفحص التقني والجاهزية:** تطابق النصوص (`values/strings.xml` مقابل `values-ar/strings.xml`)، معالجة الاستثناءات و`CancellationException`، دورة الحياة والملاحة، إدارة الذاكرة وإغلاق `PdfRenderer`، أذونات `AndroidManifest.xml`، ونظافة الأسرار وملفات `.gitignore`.

---

## 2. الملفات التي فُحصت

- **الخادم الوسيط (`server/`):**
  - `server.ts`, `server/app.ts`, `server/config/env.ts`, `server/firebase/adminApp.ts`
  - `server/middleware/auth.ts`, `server/middleware/rateLimit.ts`
  - `server/routes/catalogRoutes.ts`, `server/routes/deliveryRoutes.ts`, `server/routes/adminRoutes.ts`
  - `server/services/catalogService.ts`, `server/services/deliveryService.ts`, `server/services/uploadVerificationService.ts`, `server/services/auditLogService.ts`, `server/services/staffManagementService.ts`, `server/services/notificationDispatchService.ts`
  - `server/storage/r2Storage.ts`, `server/validation/validators.ts`, `server/types/domain.ts`, `server/utils/errors.ts`
- **قواعد الأمان والفهارس والاختبارات الخادمية (`firestore.rules`, `firestore.indexes.json`, `tests/`):**
  - `firestore.rules`, `firestore.indexes.json`
  - `tests/firestore.rules.test.ts` (`19` اختباراً على محاكي Firestore)
  - `tests/backend/backend.api.test.ts` (`16` اختبار تكامل على محاكي Firestore)
  - `tests/backend/entrypointAndRateLimit.test.ts` (`7` اختبارات)
  - `tests/backend/r2StagingLifecycle.test.ts` (`13` اختباراً)
  - `tests/backend/governanceAndNotifications.test.ts` (`5` اختبارات تكامل HTTP للحوكمة والتدقيق وإشعارات FCM)
- **تطبيق Android (`android/app/src/main/`):**
  - التهيئة والملاحة: `HaqibatiApplication.kt`, `MainActivity.kt`, `core/di/AppContainer.kt`, `core/locale/ArabicLocaleHelper.kt`, `navigation/AppDestination.kt`, `navigation/HaqibatiNavHost.kt`, `presentation/shell/MainShellScreen.kt`
  - الإشعارات (`FCM` وسجل الإشعارات المحلي): `data/notifications/HaqibatiFirebaseMessagingService.kt`, `data/notifications/NotificationChannelHelper.kt`, `data/local/LocalNotificationDataSource.kt`, `data/repository/NotificationLogRepositoryImpl.kt`, `presentation/notifications/NotificationsViewModel.kt`, `presentation/notifications/NotificationsScreen.kt`, `presentation/notifications/NotificationsUiState.kt`
  - الشاشات ومجالات العرض: `SplashViewModel.kt`, `HomeViewModel.kt`, `HomeScreen.kt`, `AboutScreen.kt`, `LibraryViewModel.kt`, `LibraryScreen.kt`, `SubjectsViewModel.kt`, `SubjectsScreen.kt`, `SectionsViewModel.kt`, `SectionsScreen.kt`, `SectionFilesViewModel.kt`, `SectionFilesScreen.kt`, `AccountViewModel.kt`, `SignInScreen.kt`, `SignUpScreen.kt`, `ForgotPasswordScreen.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `DownloadsViewModel.kt`, `DownloadsScreen.kt`, `PdfReaderViewModel.kt`, `PdfReaderScreen.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt`, `AdminPortalDialogs.kt`
  - المستودعات والبيانات والشبكات: `AuthRepositoryImpl.kt`, `AdminCatalogRepositoryImpl.kt`, `AdminGovernanceRepositoryImpl.kt`, `LocalFileOrganizationRepositoryImpl.kt`, `OfflineDownloadsRepositoryImpl.kt`, `FirebaseClientProvider.kt`, `FirebaseAuthDataSource.kt`, `BackendHttpClient.kt`, `BackendCatalogDataSource.kt`, `BackendAdminVersionDataSource.kt`, `HybridAdminCatalogDataSource`, `FirestoreAdminCatalogDataSource.kt`, `PdfFileSourceDataSource.kt`, `BrokeredPdfVersionStorageDataSource.kt`, `HttpPdfContentFetcher.kt`, `DownloadStreamFetcher.kt`, `DownloadExecutionEngine.kt`, `HaqibatiWorkerFactory.kt`, `AndroidPdfDocumentEngine.kt`, `LocalFileOrganizationDataSource.kt`, `OfflineDownloadsDataSource.kt`, `ReadingProgressDataSource.kt`, `PreferencesDataSource.kt`
  - النماذج والمصادقين: `FirestoreCatalogSchema.kt`, `AuthValidator.kt`, `AdminRole.kt`, `AdminGovernanceModels.kt`, `LocalFileOrganizationModels.kt`, `LocalNotificationModels.kt`, `StudyFileMetadata.kt`, `OfflineDownloadModels.kt`, `PdfReaderModels.kt`, `ArabicTextNormalizer.kt`
- **اختبارات Android (`android/app/src/test/` و`android/app/src/androidTest/`):**
  - `42` حزمة اختبارات وحدة JVM في `android/app/src/test/` بإجمالي **`265`** اختباراً.
  - `8` ملفات اختبارات أجهزة/واجهة RTL في `android/app/src/androidTest/` بإجمالي **`52`** اختبار `@Test`.
- **الإعدادات والموارد ومعاينة الويب:**
  - `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-ar/strings.xml`, `android/app/src/main/res/xml/locales_config.xml`, `android/app/src/main/res/values/themes.xml`
  - `.env.example`, `.gitignore`, `android/.gitignore`, `package.json`, `firebase.json`
  - اختبارات معاينة الويب: `src/data/seedCatalog.test.ts` (`5`), `src/domain/fixesVerification.test.ts` (`8`), `src/screens/pdfReaderDom.test.tsx` (`11`).

---

## 3. نتائج المراجعة الوظيفية (10 مجالات)

| # | المجال الوظيفي | الحالة الفعلية المؤكدة | التفاصيل والملاحظات الصريحة |
|---|---|---|---|
| 1 | **الزوار (Guest)** | **مكتمل ومختبر (عبر الوحدة وAPI)** | يتيح `AuthRepositoryImpl` و`LibraryScreen`/`SectionFilesScreen` التصفح دون تسجيل دخول عبر `/api/catalog/*`، وفتح ملفات PDF عبر `/api/study-files/{fileId}/versions/{versionId}/stream`. تُمنع التنزيلات المحلية للزائر في `OfflineDownloadsRepositoryImpl` و`PdfFileSourceDataSource` (`UNAUTHORIZED_ACCESS` عند غياب `ID Token`) وتظهر دعوة تسجيل الدخول في الواجهة. *(اختبارات RTL على الجهاز غير مشغّلة بيئياً).* |
| 2 | **حسابات الطلاب (Auth)** | **مكتمل ومختبر (عبر الوحدة)** | يدعم `FirebaseAuthDataSource` و`AuthRepositoryImpl` التسجيل بالبريد وكلمة المرور (مع إجبار الدور المحلي على `AdminRole.NONE` في `SignUpScreen`)، وتسجيل الدخول، واستعادة كلمة المرور، والخروج، واستعادة الجلسة عند التشغيل مع `readCurrentSession(forceTokenRefresh = true)` ومسح الجلسة عند تعطيل الحساب أو فقدانه. |
| 3 | **الصلاحيات وRBAC وإدارة المالك للمشرفين** | **مكتمل ومختبر (Rules + Backend + Android Unit)** | يفصل التطبيق والخادم و`firestore.rules` بين **`Owner`** (إدارة الفروع + إلغاء نشر المواد + عرض المشرفين ومنح/سحب صلاحية `admin` عبر `/api/admin/staff*` + قراءة سجل التدقيق `audit_logs`) و**`Admin`** (إدارة المواد/الأقسام/الملفات/الإصدارات + بث تحديثات مهمة عبر تبويب الفروع، وممنوع من `/api/admin/staff*` و`GET /api/admin/audit-logs` بـ `403 INSUFFICIENT_ROLE` ومن قراءة `audit_logs` في `firestore.rules`) و`Student`/`Guest`. |
| 4 | **المكتبة وRTL والعربية** | **مكتمل ومختبر (Backend + Web + Android Unit)** | التسلسل الهرمي الخماسي (`branches -> subjects -> sections -> study_files -> versions`) يعمل بمعرّفات `ID` ثابتة وتصفية حيّة للمنشور وغير المؤرشف، مع فرض العربية عبر `ArabicLocaleHelper` (`locales_config.xml`) و`supportsRtl="true"`. *(اختبارات Compose RTL في `androidTest` بانتظار جهاز/محاكي).* |
| 5 | **قارئ PDF والبحث العربي** | **مكتمل ومختبر (Web DOM + Android Unit)** | يدعم `PdfReaderScreen` و`PdfReaderViewModel` و`AndroidPdfDocumentEngine` عرض الصفحات عبر `PdfRenderer` بكاش صور محدود، التكبير والتمرير، حفظ موضع القراءة وتحديثه، والبحث العربي الموحّد عبر `ArabicTextNormalizer` مع معالجة واضحة لـ `NO_EXTRACTABLE_TEXT`، مدعوماً باختبارات الويب (`11/11`) واختبارات وحدة Android. |
| 6 | **التنزيل بدون إنترنت** | **مكتمل ومختبر (Backend + Android Unit)** | يتحقق التنزيل في `DownloadTaskScheduler` (`WorkManager`) و`DownloadExecutionEngine` و`LocalPdfStorageManager` من `fileSizeBytes` و`SHA-256` والترويسة `%PDF-` قبل النقل الذري من `.part` إلى المسار النهائي، ويتحقق `OfflineDownloadsRepositoryImpl` عند الفتح غير المتصل أو الشبكي من نشر الأجداد، مع احترام تعدد الإصدارات في المسار `(fileId, versionId)`. |
| 7 | **استقلالية الجهاز: التنزيلات والمفضلة المحلية و«ترتيب ملفاتي»** | **مكتمل ومختبر (Android Unit)** | ترتبط التنزيلات (`OfflineDownloadsDataSource`) وتقدم القراءة (`ReadingProgressDataSource`) والتفضيلات (`PreferencesDataSource`) **والمفضلة المحلية والترتيب الشخصي (`LocalFileOrganizationDataSource`)** بالجهاز محلياً عبر `DataStore` **دون أي `uid` أو مزامنة في Firestore**، وتُحفظ بعد إعادة إنشاء المخزن، مع أزرار صريحة في `SectionFilesScreen` و`DownloadsScreen` (**حفظ الترتيب / إلغاء التعديلات / استعادة الترتيب الافتراضي**). |
| 8 | **إدارة الإصدارات وشارة «إصدار جديد» (30 يوماً)** | **مكتمل ومختبر (Backend + Android Unit)** | تدعم `versions` الترقيم التصاعدي ونشر الإصدار النشط وإعادة نشر ملف الأب، وتحسب `NewVersionBadgePolicy` و`SectionFilesViewModel` شارة «إصدار جديد» لمدة `30` يوماً مع احترام `dismissedVersionBadge` محلياً وحماية الوصول للإصدار النشط المنشور فقط. |
| 9 | **الإشعارات وسجل الإشعارات المحلي و`POST_NOTIFICATIONS`** | **مكتمل ومختبر برمجياً (Backend + Android Unit؛ الإرسال الحي مؤجل للإطلاق)** | ترسل خدمة الخادم رسائل **Data-only** (`priority: 'high'`، دون حقل `notification`) لضمان استدعاء `HaqibatiFirebaseMessagingService.onMessageReceived` في الخلفية؛ ويحفظ `IncomingFcmMessageHandler` الإشعار محلياً أولاً في `LocalNotificationDataSource` مع منع التكرار عبر `notificationId` واستمرار الحفظ حتى عند رفض `POST_NOTIFICATIONS`، مع دعم القراءة والحذف ومسح السجل. **الإرسال الحي على جهاز فعلي مؤجل لحين توفر `google-services.json` وإعداد FCM عند الإطلاق.** |
| 10 | **لوحة الإدارة، إدارة المشرفين، بث الإشعارات، وسجل التدقيق (`audit_logs`)** | **مكتمل ومختبر (Rules + Backend + Android Unit)** | تمنع `firestore.rules` جميع عمليات `create/update/delete` على `audit_logs` من العميل وتقصر القراءة على `isOwner()`؛ ويقتصر إنشاء السجلات على Backend عبر Firebase Admin SDK (`AuditLogService`) بما يشمل تسجيل عمليات المشرفين؛ وتحصر `adminRoutes.ts` نقطتَي `/api/admin/audit-logs` و`/api/admin/staff*` بالمالك عبر `ownerGuard`؛ وتعرض واجهة Android تبويبي `STAFF` و`AUDIT_LOGS` للمالك فقط مع إتاحة بث التحديثات المهمة للمشرفين في تبويب الفروع. |

---

## 4. مراجعة النقاط الخمس المضافة وحالتها المؤكدة بعد التشغيل الفعلي

| # | الوظيفة | منفذة فعلياً؟ | الملفات / الأصناف الرئيسية | الاختبارات المشغّلة فعلياً ونتائجها | الحدود الصريحة |
|---|---|---|---|---|---|
| 1 | **المفضلة المحلية لكل جهاز** | **نعم** | `LocalFileOrganizationModels.kt`, `LocalFileOrganizationRepository.kt`, `LocalFileOrganizationDataSource.kt`, `LocalFileOrganizationRepositoryImpl.kt`, `SectionFilesViewModel.kt`/`Screen.kt`, `DownloadsViewModel.kt`/`Screen.kt` | `LocalFileOrganizationDataSourceTest` (**`2 / 2` ناجح**) + اختبارات `SectionFilesViewModelTest` و`DownloadsViewModelTest` ضمن `testDebugUnitTest` (**`265 / 265` ناجح**). | محلية عبر DataStore دون أي `uid` أو مزامنة عبر Firestore؛ اختبارات UI المجهزة على الجهاز (`androidTest`) غير مشغّلة بيئياً. |
| 2 | **ميزة «ترتيب ملفاتي» وحفظ الترتيب أو إلغاء التعديلات** | **نعم** | `LocalFileOrderingHelper`, `LocalFileOrganizationDataSource.kt`, `SectionFilesViewModel.kt`/`Screen.kt`, `DownloadsViewModel.kt`/`Screen.kt` | `LocalFileOrganizationDataSourceTest` (**`2 / 2` ناجح**) + اختبارات `SectionFilesViewModelTest` و`DownloadsViewModelTest` ضمن `testDebugUnitTest` (**`265 / 265` ناجح**). | يحافظ على ترتيب المكتبة العامة دون أي تعديل، ويدعم الملفات المنزّلة وغير المنزّلة وحفظ/إلغاء/استعادة الترتيب الافتراضي. |
| 3 | **إشعارات صدور الملفات والإصدارات وسجل الإشعارات المحلي** | **نعم** | `server/firebase/adminApp.ts`, `notificationDispatchService.ts`, `HaqibatiFirebaseMessagingService.kt`, `NotificationChannelHelper.kt`, `LocalNotificationDataSource.kt`, `NotificationLogRepositoryImpl.kt`, `NotificationsViewModel.kt`/`Screen.kt`, `AndroidManifest.xml`, `HaqibatiApplication.kt` | `governanceAndNotifications.test.ts` (**`5 / 5` ناجح**) + `LocalNotificationDataSourceTest` (**`1 / 1` ناجح**) + `HaqibatiFirebaseMessagingServiceTest` (**`1 / 1` ناجح**) + `NotificationsViewModelTest` ضمن `testDebugUnitTest` (**`265 / 265` ناجح**). | رسائل Data-only مجتازة اختبارياً؛ **الإرسال والاستقبال الحيّان لـ FCM على جهاز فعلي مؤجلان لحين توفير `google-services.json` واعتمادات الإرسال في الإطلاق**. |
| 4 | **إدارة المالك للمشرفين وصلاحياتهم** | **نعم** | `staffManagementService.ts`, `auth.ts` (`requireOwner`), `adminRoutes.ts` (`/staff`, `/staff/roles`), `AdminGovernanceRepositoryImpl.kt`, `BackendAdminVersionDataSource.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt`, `AdminPortalDialogs.kt` | `governanceAndNotifications.test.ts` (**`5 / 5` ناجح**: يشمل فشل الإبطال الجزئي `502` وفشل التدقيق الإلزامي `500`) + `AdminGovernanceRepositoryImplTest` و`AdminPortalViewModelTest` ضمن `testDebugUnitTest` (**`265 / 265` ناجح**). | محصور في `OWNER` فقط؛ يحفظ `customClaims` الأخرى، يستدعي `revokeRefreshTokens`، ويمنع تعيين `owner` أو تعديل مالك أو الحساب الحالي. |
| 5 | **سجل التدقيق (`audit_logs`) وحمايته** | **نعم** | `firestore.rules`, `server/services/auditLogService.ts`, `catalogService.ts`, `uploadVerificationService.ts`, `staffManagementService.ts`, `notificationDispatchService.ts`, `adminRoutes.ts` (`/audit-logs`), `FirestoreAdminCatalogDataSource.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt` | `tests/firestore.rules.test.ts` القسم 11 ضمن `test:rules` (**`19 / 19` ناجح**: منع `create/update/delete` من العميل وقصر القراءة على `owner`) + `governanceAndNotifications.test.ts` (**`5 / 5` ناجح**) + `AdminGovernanceRepositoryImplTest` و`AdminPortalViewModelTest` ضمن `testDebugUnitTest` (**`265 / 265` ناجح**). | الكتابة محصورة بـ Backend (Admin SDK) فقط، والقراءة محصورة في `Owner` فقط عبر `firestore.rules` و`ownerGuard` وواجهة Android مع تسجيل عمليات المشرفين في الخادم. |

---

## 5. نتائج التدقيق الأمني (12 نقطة)

| # | نقطة التدقيق الأمني | النتيجة | الدليل المرجعي في الشيفرة والاختبارات |
|---|---|---|---|
| 1 | **Custom Claims وتحديث التوكن** | **آمن / متطابق** | `FirebaseAuthDataSource` يقرأ `claims["role"]` عبر `AdminRole.fromClaim`، ويستدعي `getIdToken(true)` عند استعادة الجلسة، ويجبر عمليات الإدارة على `forceTokenRefresh = true`؛ ويُحظر تعديل `role` من العميل (`SignUp` يضبط `AdminRole.NONE`). |
| 2 | **قواعد `firestore.rules`** | **آمن / متطابق (مختبر `19/19`)** | تقفل الوصول الافتراضي؛ تقصر القراءة المباشرة للكتالوج الإداري (`branches`/`subjects`/`sections`/`study_files`) على `isAdminOrOwner()`؛ تمنع إنشاء/تحديث/حذف `versions` و`upload_sessions` تماماً من العميل؛ وتمنع جميع عمليات `create/update/delete` من العميل على `audit_logs` (`allow create, update, delete: if false;`) وتقصر `read` على `isOwner()` فقط، وتفرض القيود والحالة الثلاثية `isArchived`. |
| 3 | **فصل RBAC (`Owner / Admin / Student / Guest`)** | **آمن / متطابق (مختبر)** | إدارة الفروع وإلغاء نشر المواد وإدارة المشرفين (`/api/admin/staff*`) وقراءة `audit_logs` (`/api/admin/audit-logs` و`firestore.rules` وتبويب `AUDIT_LOGS`) محصورة في `Owner`؛ وبقية الكتالوج والرفع وبث التحديثات المهمة لـ `Owner/Admin`؛ و`Student/Guest` ممنوعان من أي كتابة إدارية. |
| 4 | **منع الكتابة المباشرة لـ `versions` و`upload_sessions` و`isPublished`** | **آمن / متطابق (مختبر)** | ترفض `firestore.rules` إنشاء `study_files` بحالة `isPublished == true` أو وجود `activeVersion*`، وتمنع تحديث الحقول المميّزة من العميل عبر `PRIVILEGED_FIELDS_LOCKED`، وتُبقي النشر وإصدار الملفات بيد الخادم عبر Firebase Admin SDK. |
| 5 | **تحقق Backend من التوكن مع `checkRevoked`** | **آمن / متطابق (مختبر)** | تستدعي `server/middleware/auth.ts` الدالة `verifyIdToken(token, true)` للتحقق من الصلاحية والإلغاء قبل أي طلب مصادق عليه أو إداري، مع `requireAdminOrOwner` و`requireOwner` (`ownerGuard`). |
| 6 | **التحقق الحي من الطبقات الخمس** | **آمن / متطابق (مختبر)** | في `catalogService.ts` و`deliveryService.ts` يتم فحص تسلسل الأهل حيّاً دون كاش (`branch -> subject -> section -> studyFile -> version`) مع اشتراط `isPublished === true` و`isArchived === false` في جميع النقاط العامة والبث والتنزيل. |
| 7 | **حماية `upload-slot` و`verify` و`publish`** | **آمن / متطابق (مختبر)** | تتحقق `uploadVerificationService.ts` من `requireAdminOrOwner`، ومهلة الجلسة (`600` ثانية)، وقفل `activeAttemptId` (`Fencing` + `ETag`/`412`)، ورفض الحقول المحظورة و`UNSPECIFIED`، وإعادة فحص الإثبات داخل معاملة النشر لمنع TOCTOU. |
| 8 | **عدم تسريب روابط/مفاتيح R2** | **آمن / متطابق** | لا تُخزَّن مفاتيح R2 في تطبيق Android أو الويب، وتُعزَل في `process.env` على الخادم مع إجبار `HTTPS` لـ `R2_ENDPOINT`؛ ولا تُرجَع روابط أو مفاتيح التخزين الداخلية في `/api/catalog/*`، بل يتوسّط الخادم البث والتنزيل عبر `/api/study-files/.../{stream,download}`. |
| 9 | **تحقق الحجم و`SHA-256` و`%PDF-`** | **آمن / متطابق (مختبر)** | يُتحقَّق من الحد الأقصى للحجم (`100 MiB`)، وبصمة `SHA-256`، والترويسة `%PDF-` عند القراءة الأولية، وعند التحقق على الخادم (`uploadVerificationService.ts`)، وعند التنزيل المحلي (`DownloadExecutionEngine` و`LocalPdfStorageManager`). |
| 10 | **منع الملفات غير المنشورة أو المؤرشفة** | **آمن / متطابق (مختبر)** | يُحجَب أي ملف غير منشور أو مؤرشَف أو يتبع أصلاً غير منشور أو مؤرشفاً عن الضيوف والطلاب في Backend و`BackendCatalogDataSource` و`OfflineDownloadsRepositoryImpl` والقارئ. |
| 11 | **سلامة رسائل الأخطاء وسجلات التشغيل** | **آمن / متطابق** | تعرض واجهات Android رسائل عربية واضحة دون كشف تتبّع مكدس أو معرفات سرية أو ترويسات `Authorization`، وأظهر الفحص خلوّ شيفرة الإنتاج من طباعة التوكنات أو المفاتيح. |
| 12 | **وضع `Fail-Closed`** | **آمن / متطابق (مختبر)** | عند غياب `HAQIBATI_BACKEND_BASE_URL` يقف التطبيق بوضع `Fail-Closed` دون بدائل مفتوحة؛ ويرفض `BackendHttpClient` الاتصال غير المشفّر وإعادة التوجيه؛ ويرفض `FirebaseClientProvider` أي مشروع غير `student-learning-platform-2026`؛ ويضبط الخادم `TRUST_PROXY = false` افتراضياً. *(تنبيه بيئي في اعتماديات التطوير فقط: أشار `bun audit` إلى `@grpc/grpc-js@1.9.16` داخل حزمة التطوير `firebase` المخصصة لمحاكي القواعد، بينما يعتمد الخادم التشغيلي `firebase-admin` على `@grpc/grpc-js@1.14.7` المرقّع).* |

---

## 6. نتائج الفحص التقني والجاهزية

- **الترجمة وتطابق الموارد النصية:** فُحصت جميع عناصر `<string>` في `android/app/src/main/res/values/strings.xml` مقابل `values-ar/strings.xml` فحصاً آلياً شاملاً: **`442 / 442` متطابق بالكامل** (`0` مفقود في العربية، `0` زائد في العربية، `0` عدم تطابق في معاملات التنسيق `%s/%d`).
- **معالجة الاستثناءات والإلغاء:** تمرّر جميع مستودعات ومصادر بيانات Kotlin الاستثناء `CancellationException` دون تغليفها في أخطاء عادية، مع حماية الحفظ النهائي في `NonCancellable` عند الاقتضاء وإغلاق اتصالات الشبكة وتنظيف ملفات `.part` عند الإلغاء.
- **إدارة الذاكرة و`PdfRenderer`:** يستخدم `AndroidPdfDocumentEngine` عدّاد مراجع (`refCount`) لإغلاق `PdfRenderer` و`ParcelFileDescriptor` بأمان وتفادي الإغلاق أثناء التصيير، مع كاش صور مقيد الحجم (`LruCache` بسعة `24 MiB`).
- **أذونات `AndroidManifest.xml` وأقل الصلاحيات:** تقتصر الأذونات على `INTERNET` و`ACCESS_NETWORK_STATE` و`POST_NOTIFICATIONS` (لـ Android 13+)، مع `android:allowBackup="false"` و`android:supportsRtl="true"` و`android:localeConfig="@xml/locales_config"`.
- **نظافة الأسرار والملفات المؤقتة:** خلت `android/app/src/main` و`server` و`src` و`.env.example` من أي مفاتيح R2 أو مفاتيح Firebase خاصة أو `BEGIN PRIVATE KEY` أو `google-services.json`؛ كما يغطي `/.gitignore` و`/android/.gitignore` استبعاد `dist/` و`android/.gradle/` و`android/app/build/` و`*.apk` و`google-services.json`.

---

## 7. التعديلات والإصلاحات المطبقة خلال المرحلة التاسعة (بما فيها إصلاحات المراجعة وتعديلات الاختبارات الثلاثة)

### 7.1. إصلاحات الملاحظات الأربع في المراجعة المستقلة
1. **حماية `audit_logs` من الكتابة عبر العميل (`firestore.rules`, `FirestoreAdminCatalogDataSource.kt`):** منع `create/update/delete` كلياً من العميل وإزالة استدعاءات التسجيل من العميل وحصر الكتابة بـ Backend (`AuditLogService`).
2. **حصر قراءة `audit_logs` بالمالك (`firestore.rules`, `server/routes/adminRoutes.ts`, `AdminGovernanceRepositoryImpl.kt`, `AdminPortalViewModel.kt`, `AdminPortalScreen.kt`):** قصر القراءة في Firestore على `isOwner()`، وحماية `GET /api/admin/audit-logs` بـ `ownerGuard` (`403 INSUFFICIENT_ROLE` للمشرف العادي)، وإخفاء تبويب `AUDIT_LOGS` عن غير المالك مع إبقاء بث التحديثات المهمة متاحاً للمشرفين في تبويب الفروع.
3. **إصلاح استقبال FCM في الخلفية (`server/firebase/adminApp.ts`, `notificationDispatchService.ts`, `HaqibatiFirebaseMessagingService.kt`, `NotificationChannelHelper.kt`, `LocalNotificationDataSource.kt`, `NotificationLogRepository.kt`, `NotificationLogRepositoryImpl.kt`):** التحويل إلى رسائل **Data-only** (`android.priority = 'high'`) دون حقل `notification` لضمان تشغيل `onMessageReceived` في الخلفية وحفظ السجل محلياً أولاً عبر `saveNotificationIfAbsent` (مع منع التكرار بـ `notificationId` واستمرار الحفظ حتى عند رفض `POST_NOTIFICATIONS`).
4. **وضوح نتيجة تعديل صلاحيات المشرف وموثوقية التدقيق (`staffManagementService.ts`, `errors.ts`, `BackendAdminVersionDataSource.kt`, `AuthMessageResolver.kt`, `AdminPortalViewModel.kt`, `strings.xml`):** اشتراط توفر `AuditLogService` (`503 AUDIT_SERVICE_UNAVAILABLE`)، وإرجاع `502 STAFF_ROLE_CLAIMS_UPDATED_TOKEN_REVOKE_FAILED` دون rollback عند فشل إبطال الجلسات بعد تحديث Claims، وإرجاع `500 STAFF_ROLE_UPDATED_AUDIT_LOG_FAILED` عند فشل التسجيل الإلزامي في التدقيق.

### 7.2. الإصلاحات الفعلية لملفات اختبارات Android الثلاثة أثناء التشغيل الفعلي لـ `testDebugUnitTest`
أظهر التشغيل الفعلي الأول لـ `./gradlew testDebugUnitTest` ثلاث مشكلات محصورة في ملفات الاختبار الآتية دون أخطاء في شيفرة الإنتاج، وجرى إصلاحها دون حذف أي اختبار أو إضعاف أي شرط تحقق:

1. **`HaqibatiFirebaseMessagingServiceTest.kt`:**
   - **العطل المثبت:** فشل الترجمة بسبب عدم تمرير معامل `dispatcherProvider: DispatcherProvider` عند إنشاء `NotificationLogRepositoryImpl`.
   - **الإصلاح:** استيراد `DispatcherProvider` وتمرير مزوّد اختباري مربوط بالمجدول نفسه (`StandardTestDispatcher(testScheduler)`) إلى `NotificationLogRepositoryImpl`.
2. **`LocalFileOrganizationDataSourceTest.kt`:**
   - **العطل المثبت:** استثناء `OkioStorage` (`IllegalStateException: There are multiple DataStores active for the same file`) في اختبار إعادة فتح المخزن، لأن `scope1.cancel()` لم يكن ينتظر اكتمال إغلاق `DataStore` على المجدول الاختباري قبل إنشاء المثيل الثاني للملف نفسه.
   - **الإصلاح:** ربط النطاقين بـ `SupervisorJob()` واستدعاء `job1.cancelAndJoin()` متبوعاً بـ `advanceUntilIdle()` قبل إعادة فتح `PreferenceDataStoreFactory` على الملف نفسه (وكذلك `job2.cancelAndJoin(); advanceUntilIdle()` في النهاية).
3. **`LocalNotificationDataSourceTest.kt`:**
   - **العطل المثبت:** نفس تعارض `OkioStorage` عند محاكاة إعادة التشغيل على ملف `local_notifications.preferences_pb` نفسه.
   - **الإصلاح:** استخدام `SupervisorJob()` مع `job1.cancelAndJoin()` و`advanceUntilIdle()` قبل إعادة فتح المخزن، وإنهاء `job2` بالطريقة نفسها.

---

## 8. الفصل الصريح لحالة الاختبارات في المرحلة التاسعة

### أ. الاختبارات والفحوصات التي نُفّذت ونجحت فعلياً في بيئة العمل الحالية

| الاختبار / الفحص | الأمر المنفّذ فعلياً | النتيجة الفعلية | التفاصيل |
|---|---|---|---|
| **اختبارات قواعد Firestore (على المحاكي الفعلي)** | `npm run test:rules` *(مع `JDK 21`)* | **نجح (`19 / 19`)** | `10` حزم، `0` فشل، `0` تخطٍ؛ يشمل القسم `11` لمنع `create/update/delete` على `audit_logs` من العميل وقصر `read` على `owner`. |
| **اختبارات Backend (على المحاكي الفعلي + الوحدات)** | `npm run test:backend` *(مع `JDK 21`)* | **نجح (`36 / 36`)** | `5` حزم (`0` فشل، `0` تخطٍ): `16` في `backend.api.test.ts` + `7` في `entrypointAndRateLimit.test.ts` + `13` في `r2StagingLifecycle.test.ts`. |
| **اختبارات الحوكمة والتدقيق وإشعارات FCM** | `npx tsx --test tests/backend/governanceAndNotifications.test.ts` | **نجح (`5 / 5`)** | `0` فشل، `0` تخطٍ؛ يغطي `403` للمشرف على `/audit-logs` وإتاحته للمالك، ورسائل FCM Data-only، وأخطاء `502` و`500` في إدارة المشرفين. |
| **اختبارات معاينة الويب** | `npx tsx --test src/data/seedCatalog.test.ts src/domain/fixesVerification.test.ts src/screens/pdfReaderDom.test.tsx` | **نجح (`24 / 24` فحصاً)** | `5` في `seedCatalog.test.ts` + `8` في `fixesVerification.test.ts` + `11` فحص DOM تفاعلي في `pdfReaderDom.test.tsx` (`0` فشل). |
| **اختبارات وحدة Android الكاملة** | `./gradlew testDebugUnitTest --no-daemon` *(مع `JDK 17 + Android SDK 35`)* | **نجح (`265 / 265`)** | `42` حزمة اختبار XML، `0` فشل، `0` أخطاء، `0` تخطٍ. |
| **إعادة التشغيل المركّزة للاختبارات الثلاثة المعدّلة** | `./gradlew testDebugUnitTest --tests ...HaqibatiFirebaseMessagingServiceTest --tests ...LocalFileOrganizationDataSourceTest --tests ...LocalNotificationDataSourceTest --rerun-tasks --no-daemon` | **نجح (`4 / 4`)** | نُفّذت `24` مهمة بالكامل: `HaqibatiFirebaseMessagingServiceTest` (`1/1`)، `LocalFileOrganizationDataSourceTest` (`2/2`)، `LocalNotificationDataSourceTest` (`1/1`)؛ وهي جزء من إجمالي `265` اختباراً لا تُحتسب مرتين. |
| **فحص Android Lint** | `./gradlew lintDebug --no-daemon` | **نجح (`BUILD SUCCESSFUL`)** | **`0` أخطاء و`52` تحذيراً** (تحذيرات إصدارات اعتماديات وملاحظات غير معطّلة). |
| **بناء Android Debug** | `./gradlew assembleDebug --no-daemon` | **نجح (`BUILD SUCCESSFUL`)** | اكتملت المهام (`37/37`) وبُني `app-debug.apk` محلياً داخل `android/app/build/outputs/apk/debug/` (دون إنشاء ZIP أو APK تسليم). |
| **فحص الأنواع وبناء الويب** | `npm run lint` (`tsc --noEmit`) و`npm run build` (`vite build --mode production`) | **نجح (`EXIT=0`)** | `0` أخطاء ترجمة أو أنواع، وبناء إنتاجي سليم للويب. |
| **تدقيق تطابق نصوص Android** | فحص XML بين `values/strings.xml` و`values-ar/strings.xml` | **نجح (`442 / 442`)** | تطابق تام في المفاتيح ومعاملات التنسيق `%s/%d` دون نقص أو زيادة. |

- **الإجمالي المؤتمت للاختبارات/الفحوصات المشغّلة والناجحة فعلياً:** **`349 / 349`** (`19` Rules + `36` Backend + `5` Governance/Notifications + `24` فحص ويب + `265` Android Unit Tests، مع تأكيد إعادة تشغيل الاختبارات الثلاثة المعدّلة `4 / 4`)، إضافةً إلى نجاح `lintDebug` (`0` أخطاء، `52` تحذيراً)، و`assembleDebug`، و`npm run lint`، و`npm run build`، وتطابق `442 / 442` في `strings.xml`. **`0` اختبارات منفذة فاشلة.**

---

### ب. الاختبارات التي لم تُشغّل في بيئة الاختبار الحالية وأسبابها الدقيقة

| الحزمة غير المنفذة | العدد المتوفر في الكود | السبب البيئي الدقيق لعدم التشغيل |
|---|---|---|
| `./gradlew connectedDebugAndroidTest` (`android/app/src/androidTest/`) | **`52`** اختبار `@Test` عبر **`8`** ملفات | عدم توفر جهاز Android متصل عبر `adb`، وغياب محاكي Android صالح وعدم توفر دعم `/dev/kvm`، وغياب ملف `android/app/google-services.json` في بيئة الاختبار الحالية. |

---

### ج. الاختبارات الميدانية المؤجلة (تتطلب جهازاً حقيقياً أو إعدادات خدمات خارجية عند الإطلاق)

1. **اختبارات الأجهزة والواجهة على Android (`connectedDebugAndroidTest` — `52` اختباراً):** تشغيل ملفات `AccountAuthRtlTest`, `AdminPortalRtlTest`, `HomeScreenRtlTest`, `LibraryCatalogRtlTest`, `MainShellRtlTest`, `PdfReaderRtlTest`, `SectionFilesDownloadsRtlTest`, و`WorkManagerDownloadTest` على جهاز فعلي أو محاكي مزوّد بـ KVM.
2. **الإرسال والاستقبال الحي لإشعارات FCM:** تأكيد وصول إشعارات `NEW_FILE` و`NEW_VERSION` و`IMPORTANT_UPDATE` حياً في المقدمة والخلفية على جهاز Android حقيقي بعد إضافة `android/app/google-services.json` وتفعيل اعتمادات FCM على الخادم.
3. **الربط والتشغيل الحي للخدمات السحابية (بعد الاعتماد والانتقال للمرحلة العاشرة):**
   - نشر `firestore.rules` و`firestore.indexes.json` إلى مشروع `student-learning-platform-2026` (محظور خلال المرحلة التاسعة).
   - تهيئة حاوية **Cloudflare R2** مع قاعدة Lifecycle لتنظيف `uploads-staging/`، وتشغيل Backend D2 عبر رابط `HTTPS` موثوق وتمريره إلى `HAQIBATI_BACKEND_BASE_URL` مع مطابقة `TRUST_PROXY`.
   - اختبار دورة الرفع/التحقق/النشر والبث والتنزيل الحي عبر `WorkManager` مع قطع الشبكة وإعادتها، وتجربة `PdfRenderer` لملفات عربية كبيرة ومصوّرة، وتدوير الشاشة/قتل العملية على جهاز حقيقي.

---

## 9. الحكم النهائي الصريح على المرحلة التاسعة

- **الحكم النهائي:** **اكتملت المرحلة التاسعة محلياً بدرجة «مكتملة في بيئة الاختبار المحلية مع متطلبات ميدانية وخارجية مؤجلة للإطلاق»**؛ حيث جُهّزت البيئة المحلية كاملة وشُغّلت جميع حزم الاختبارات القابلة للتنفيذ محلياً بنجاح تام (`349 / 349` اختباراً وفحصاً مؤتمتاً، و`lintDebug` بصفر أخطاء، و`assembleDebug` بنجاح، و`npm run lint`/`build` بنجاح، وتطابق نصوص `442 / 442`) مع معالجة وإغلاق جميع الأعطال الفعلية المكتشفة في اختبارات الوحدة الثلاثة، ودون أي نشر خارجي أو بدء للمرحلة العاشرة.
- **ما بقي خارج نطاق هذه الحاوية ومؤجل لما قبل النشر العام:**
  1. تشغيل `./gradlew connectedDebugAndroidTest` (`52` اختباراً) على جهاز Android فعلي أو محاكي يدعم `/dev/kvm`.
  2. ربط `android/app/google-services.json`، وتهيئة Cloudflare R2 وBackend المنشور عبر HTTPS، وإجراء الاختبار الحي لإشعارات FCM والتنزيل والعرض على جهاز حقيقي قبل الإطلاق في المرحلة العاشرة.
