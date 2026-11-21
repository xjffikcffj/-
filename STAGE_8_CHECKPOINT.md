# نقطة التوقف — المرحلة الثامنة: إدارة إصدارات الملفات والتحديثات وشارة «إصدار جديد» (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** مكتملة برمجياً (Android Native — Kotlin + Jetpack Compose) بعد تنفيذ **الدفعة الثالثة ودفعة التحسين الختامية** من المرحلة الثامنة، **ولم يُشغَّل التجميع أو الاختبارات فعلياً** لعدم توفر JDK / Android SDK / Gradle في بيئة التنفيذ الحالية؛ كما أن **قواعد أمان Firestore المنشورة** و**وسيط Cloudflare R2 الخادمي** غير مفعّلين بعد.

---

## 1. الفصل الواضح بين: ما اكتمل برمجياً vs ما كُتبت له اختبارات vs ما شُغّل فعلياً

1. **ما اكتمل برمجياً (Code Complete):**
   - نموذج الإصدارات المستقل `StudyFileVersionMetadata` (باستخدام `id`) وربط `study_files/{fileId}/versions/{versionId}` ومؤشرات الإصدار الفعّال (`activeVersionId` / `activeVersionNumber` / `activeVersionLabel` / `activeVersionPublishedAt`).
   - توحيد توقيع `createStudyFileVersion(fileId, versionNumber, versionLabel, versionNotes, publishAsActive, customVersionId)` بين الواجهة والمستودع و`AdminPortalViewModel` وجميع الـ Fakes.
   - معاملات `Firestore.runTransaction` في `FirestoreAdminCatalogDataSource`:
     - حجز ومنع تكرار `versionNumber` عبر `registeredVersionNumbers` بتوافق تام مع Firebase BoM 33.7.0.
     - ضمان نشر وتفعيل إصدار واحد فقط دون حذف أو استبدال الإصدارات السابقة أو مفاتيح R2 التاريخية.
     - منع إعادة نشر أو تفعيل ملف أب مؤرشف (`setStudyFilePublished` + `publishStudyFileVersion`) داخل معاملة ذرية وإرجاع `FILE_ARCHIVED_CANNOT_PUBLISH_VERSION`.
   - جعل `pdfVersionStorageBroker: PdfVersionStorageBroker` معامل بناء إلزامياً غير قابل للـ `null` في `AdminCatalogRepositoryImpl` (تصميم Fail-Closed يمنع تجاوز فحص الوسيط، مع استمرار عمل المسودات وتعديل البيانات الوصفية عند تعذر تهيئة R2).
   - عزل التنزيلات المحلية وتقدم القراءة حسب `(fileId, versionId)` (المرحلة السابعة محفوظة دون كسر؛ نشر `v2` لا يستبدل `v1` المحلي).
   - شارة «إصدار جديد» (`NewVersionBadgePolicy`, `SectionFilesViewModel`, `SectionFilesScreen`):
     - نافذة شهر واحد (`30 يوماً`) محلياً لكل جهاز فقط، وتختفي عند تنزيل الإصدار الفعّال على نفس الجهاز فقط، وتبقى عند الفشل أو الإلغاء أو التصفح عبر الإنترنت أو وجود نسخة أقدم فقط.
     - مؤقت `delay` واحد مجدول لأقرب انتهاء صلاحية دون Polling مستمر، وإعادة التقييم عند العودة من الخلفية عبر `Lifecycle.Event.ON_RESUME` (`onForegroundResumed()`).
     - معالجة انحراف ساعة الجهاز (التأخير عن توقيت الخادم والقفز المفاجئ للأمام أو للخلف عبر المرساة الرتيبة `resolveTrustedNowEpochMillis`) دون إخفاء الشارة مبكراً اعتماداً على ساعة غير موثوقة.
   - تحديث `AdminPortalScreen.kt` (بانر تبويب `PDF_FILES` وبطاقة `AdminStudyFileCard`) لعرض حالة `uiState.r2BrokerStatus` الفعلية دون الإيحاء باكتمال تدفق رفع PDF والتحقق الخادمي عند `CONFIGURED`.
   - **توحيد وإلزامية إثبات ملف PDF (`AdminCatalogValidator.hasReliableUploadedPdfProof`):**
     - أصبحت `checksumSha256` إلزامية وترفض `null` والفراغ وأي قيمة لا تطابق 64 محرفاً سداسياً عشرياً (`^[a-fA-F0-9]{64}$`).
     - التحقق الصارم من مطابقة `r2ObjectKey` لمسار `study-files/{fileId}/versions/{versionId}/{name}.pdf` (أو المعادل المرمز بالروابط) ورفض أي رموز خطرة (`..`, `://`, `\`, `?`, `#`, مسافات، `fake`, `placeholder`).
     - ربط `StudyFileVersionMetadata.hasUploadedPdfBinary` و`FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof` بالدالة الموحدة في `AdminCatalogValidator` لضمان تطابق الواجهة والمستودع ومصدر بيانات Firestore بالكامل.
   - **منع النشر الوهمي وفشل الوسيط بأمان (Fail-Closed R2 Broker):**
     - تحديث `BrokeredPdfVersionStorageDataSource` بحيث لا يُعتبر وجود `brokerEndpointBaseUrl` (حتى لو كان رابط `https://`) دليلاً على جاهزية R2، وتظل `getBrokerStatus()` تُرجع `AWAITING_SECURE_BROKER_INTEGRATION`.
     - إضافة عقد التحقق الخادمي `VerifiedPdfBinaryProof` والدالة `verifyUploadedPdfBinary(fileId, versionId)` إلى `PdfVersionStorageBroker`؛ تفشل بأمان (`SECURE_SOURCE_NOT_CONFIGURED`) في `BrokeredPdfVersionStorageDataSource` ما لم تتوفر خدمة رفع وتحقق خادمية حقيقية.
     - تعديل `AdminCatalogRepositoryImpl.publishStudyFileVersion` و`FirestoreAdminCatalogDataSource.publishStudyFileVersion` لرفض النشر ما لم يسترجعا إثباتاً خادمياً موثقاً (`VerifiedPdfBinaryProof`) يطابق سجل الإصدار في Firestore عبر `matchesVerifiedPdfBinaryProof`.
   - **حماية الملف الأب (`study_files/{fileId}`):**
     - منع إنشاء ملف دراسي منشور مباشرة (`createStudyFileMetadata(..., isPublished = true)`) وإرجاع `AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION`، مع إبقاء إنشاء المسودات (`isPublished = false`) والإصدارات كمسودات عاملاً بشكل طبيعي (وكذلك إبقاء مفتاح النشر الفوري معطلاً في `StudyFileEditorDialog` عند الإنشاء).
     - فحص `setStudyFilePublished(..., true)` داخل معاملة `Firestore.runTransaction` (كل القراءات قبل التحديثات) للتحقق عبر `validateStudyFilePublishTransition` من عدم أرشفة الأب ووجود `activeVersionId` يشير إلى إصدار منشور غير مؤرشف يطابق `fileId` و`activeVersionNumber` ويستوفي `hasReliableUploadedPdfProof`.
   - **إصلاح أرشفة واستعادة الملفات الدراسية (`setStudyFileArchived` + `buildArchiveStateUpdatePayload`):**
     - تعمل `FirestoreAdminCatalogDataSource.setStudyFileArchived` داخل معاملة `Firestore.runTransaction` وتقرأ وثيقة الملف قبل التحديث.
     - عند الأرشفة (`isArchived = true`) تُضبط `isPublished = false` ذرياً؛ وعند الاستعادة (`isArchived = false`) تبقى `isPublished = false` ولا يُعاد النشر تلقائياً بل يُشترط المرور عبر `setStudyFilePublished(fileId, true)`.
     - تبقى وثائق الإصدارات (`versions`) ومؤشرات الإصدار الفعّال والنسخ المحلية وتقدم القراءة لدى الطلاب دون حذف.
   - **استعلام ملفات الطلاب (`FirestoreCatalogDataSource.fetchPublishedStudyFiles`):**
     - أُضيف شرط `.whereEqualTo(Fields.IS_ARCHIVED, false)` داخل استعلام Firestore نفسه بجانب `sectionId == ref` و`isPublished == true` (مع بقاء فلترة `FirestoreCatalogMapper.mapStudyFiles` كطبقة دفاعية).
   - **إلزامية `activeVersionNumber` في `validateStudyFilePublishTransition` وتوحيده في الواجهة (`hasValidActiveVersionForPublish`):**
     - رفض `activeVersionNumber == null` أو `< 1` أو غير المطابق لـ `activeVersion.versionNumber` مع استمرار فحص `fileId` وحالة الأرشفة والنشر وإثبات PDF.
     - إضافة `StudyFileMetadata.hasValidActiveVersionForPublish` (`!activeVersionId.isNullOrBlank() && activeVersionNumber != null && activeVersionNumber >= 1`) وربط زر النشر في `AdminStudyFileCard` بها لتعطيل النشر عند نقصان أو عدم صلاحية `activeVersionNumber` مع بقاء زر «إلغاء النشر» مفعّلاً للملفات المنشورة حتى لو كانت بيانات إصدارها غير مكتملة.
   - **تحديث واجهة المشرف (`AdminPortalScreen.kt` — `AdminStudyFileCard`):**
     - تعطيل زر «نشر» للملف غير المنشور إذا غاب `activeVersionId` أو لم يكن `activeVersionNumber >= 1` أو كان الملف مؤرشفاً، وعرض رسالة عربية واضحة تشرح سبب التعطيل (`admin_study_file_publish_disabled_archived` / `admin_study_file_publish_disabled_no_active_version`)، مع بقاء الحماية في المستودع ومصدر البيانات.
   - تطابق مفاتيح `R.string.*` (`346` مفتاحاً متطابقاً في `values/strings.xml` و`values-ar/strings.xml`).

2. **ما كُتبت له اختبارات (Tests Written):**
   - اختبارات الدفعة الثالثة والتحسين الختامي: أرشفة ملف منشور (`isPublished = false`)، استعادة ملف مؤرشف دون إعادة نشره، منع نشر ملف بلا إصدار نشط، رفض `activeVersionNumber = null` و`0` و`-1` واختلاف رقم الإصدار، استبعاد الملفات المؤرشفة من نتائج واستعلام الطلاب، مزامنة `RecordingAdminRemoteDataSource.setStudyFilePublished` مع شروط التحقق الحقيقية، اختبارات `AdminPortalRtlTest.kt` (تعطيل النشر للمؤرشف/ناقص الإصدار/صاحب `activeVersionNumber = 0`، وتفعيل «نشر» للمسودة الجاهزة و«إلغاء النشر» للمنشور)، واستمرار الاحتفاظ بالنسخ المحلية وتقدم قراءة PDF في `OfflineDownloadsRepositoryImplTest.kt` و`ReadingProgressRepositoryImplTest.kt`.
   - اختبارات إثبات PDF والتحقق الخادمي وحماية الملف الأب في `FirestoreAdminCatalogMapperTest.kt` و`AdminCatalogRepositoryImplTest.kt` و`AdminPortalViewModelTest.kt`.
   - اختبارات شارة «إصدار جديد»، العودة للواجهة (`onForegroundResumed`)، وانحراف الساعة (`NewVersionBadgePolicyTest.kt` و`SectionFilesViewModelTest.kt`).
   - اختبارات عزل الإصدارات وقارئ PDF وحذف التنزيلات وتأكيده (`ReadingProgressRepositoryImplTest.kt`، `PdfReaderViewModelTest.kt`، `DownloadsViewModelTest.kt`، `AppDestinationTest.kt`، `SectionFilesDownloadsRtlTest.kt`، `PdfReaderRtlTest.kt`).

3. **ما شُغّل فعلياً من اختبارات وعمليات تحقق:**
   - **تم تشغيله فعلياً في هذه البيئة:** الفحص الساكن الشامل على ملفات Kotlin وموارد XML للتحقق من:
     - تطابق جميع الاستيرادات والتوقيعات وتغطية جميع قيم `AdminValidationError` في `AuthMessageResolver`.
     - تطابق جميع مفاتيح `R.string.*` (`346` مفتاحاً في `values/strings.xml` و`values-ar/strings.xml` مع `0` مراجع مفقودة).
     - عدم وجود ملف `firestore.rules` أو روابط/بصمات/بيانات سرية وهمية.
   - **لم يُشغَّل فعلياً:** أي تجميع أو اختبار برمجي أو واجهة (`assembleDebug`, `lintDebug`, `testDebugUnitTest`, `connectedDebugAndroidTest`) لعدم توفر JDK وAndroid SDK وGradle في الحاوية الحالية.

---

## 2. القيود الأمنية والخدمات المعلّقة عمداً ومتطلبات التفعيل الحقيقي

- **فهرس Firestore لاستعلام `study_files`:** الاستعلام الجديد يجمع ثلاثة شروط تساوٍ (`sectionId == ref`, `isPublished == true`, `isArchived == false`) دون `orderBy`. ورغم دعم Firestore أحياناً لدمج الفهارس المفردة (zig-zag merge) لشروط `==` فقط، يُنصح/قد يُطلب في Firebase Console إنشاء فهرس مركب (Composite Index) على المجموعة `study_files` للحقول `(sectionId ASC, isPublished ASC, isArchived ASC)`، كما يجب أن تحتوي كل وثائق `study_files` القديمة على الحقل `isArchived = false` حتى يشملها شرط التساوي.
- **قواعد أمان Firestore (`firestore.rules`) غير موجودة في المستودع:** القواعد السابقة كانت ترفض جميع العمليات (`deny-all`) ولم تُنشأ أو تُنشر قواعد جديدة في هذا المستودع. قبل الانتقال للإنتاج، يجب كتابة ونشر `firestore.rules` بحيث تقصر قراءة الطلاب على الوثائق المنشورة غير المؤرشفة، وتمنع أي كتابة مباشرة من العميل لحقول التحقق (`r2ObjectKey`, `fileSizeBytes`, `checksumSha256`) أو تفعيل النشر (`isPublished = true`, `activeVersion*`).
- **وسيط Cloudflare R2 والخادم الوسيط غير مفعّلين بعد:** يبقى الوسيط في حالة `AWAITING_SECURE_BROKER_INTEGRATION`؛ لا توجد مفاتيح أو روابط R2 وهمية في العميل، ولم يُستخدم Firebase Storage في أي موضع.

---

## 3. قائمة الملفات المرتبطة بالمرحلة الثامنة

### ملفات الإنتاج والتكوين (Production):
- `android/app/src/main/java/com/studentlearning/platform2026/core/di/AppContainer.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/model/AdminFailure.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/model/NewVersionBadgePolicy.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/model/StudyFileMetadata.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/repository/AdminCatalogRepository.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/repository/PdfVersionStorageBroker.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/repository/ReadingProgressRepository.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/domain/validation/AdminCatalogValidator.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/local/ReadingProgressDataSource.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/pdf/BrokeredPdfVersionStorageDataSource.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreCatalogSchema.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreCatalogMapper.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreCatalogDataSource.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreAdminCatalogMapper.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreAdminCatalogDataSource.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/repository/AdminCatalogRepositoryImpl.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImpl.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/account/AuthMessageResolver.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/admin/AdminPortalUiState.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/admin/AdminPortalViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/admin/AdminPortalDialogs.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/admin/AdminPortalScreen.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesUiState.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/library/SectionFilesScreen.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/downloads/DownloadsUiState.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/downloads/DownloadsViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/downloads/DownloadsScreen.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderUiState.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/navigation/AppDestination.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/navigation/HaqibatiNavHost.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/main/MainShellScreen.kt`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values-ar/strings.xml`

### ملفات الاختبارات والـ Fakes:
- `android/app/src/test/java/com/studentlearning/platform2026/fakes/FakeRepositories.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/domain/model/NewVersionBadgePolicyTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/domain/validation/AdminCatalogValidatorTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreCatalogMapperTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreCatalogDataSourceTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreAdminCatalogMapperTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/AdminCatalogRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/OfflineDownloadsRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/admin/AdminPortalViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/downloads/DownloadsViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/navigation/AppDestinationTest.kt`
- `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/AdminPortalRtlTest.kt`
- `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/SectionFilesDownloadsRtlTest.kt`
- `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/PdfReaderRtlTest.kt`

---

## 4. أوامر التحقق المطلوب تشغيلها في بيئة Android Studio / JDK 17 + SDK 35

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```
