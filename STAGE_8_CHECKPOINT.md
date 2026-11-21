# نقطة التوقف — المرحلة الثامنة: إدارة إصدارات الملفات والتحديثات وشارة «إصدار جديد» (`STAGE_8_CHECKPOINT.md`)

**حالة المرحلة:** مكتملة برمجياً (Android Native — Kotlin + Jetpack Compose) بعد تنفيذ دفعة الإصلاح النهائية المحددة، **ولم يُشغَّل التجميع أو الاختبارات فعلياً** لعدم توفر JDK / Android SDK / Gradle في بيئة التنفيذ الحالية؛ كما أن **قواعد أمان Firestore المنشورة** و**وسيط Cloudflare R2 الخادمي** غير مفعّلين بعد.

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
   - تطابق مفاتيح `R.string.*` في `values/strings.xml` و`values-ar/strings.xml`.

2. **ما كُتبت له اختبارات (Tests Written):**
   - إصلاح تجميع `AdminCatalogRepositoryImplTest.kt` بعد إضافة دوال `PdfVersionStorageBroker` الحقيقية (`buildVersionObjectKey` و`requestVersionUploadSlot`) وإزالة `issueAuthorizedPdfReadUrl` غير الموجودة وتمرير الوسيط غير القابل للـ `null` لجميع اختبارات المستودع (حيث كانت الكائنات المجهولة السابقة في `AdminCatalogRepositoryImplTest.kt` تمنع التجميع قبل هذا الإصلاح).
   - اختبارات حجب إعادة نشر الملف الأب المؤرشف في `FirestoreAdminCatalogMapperTest.kt` و`AdminCatalogRepositoryImplTest.kt` و`AdminPortalViewModelTest.kt`.
   - اختبارات شارة «إصدار جديد»، العودة للواجهة (`onForegroundResumed`)، وانحراف الساعة (`NewVersionBadgePolicyTest.kt` و`SectionFilesViewModelTest.kt`).
   - اختبارات عزل الإصدارات وقارئ PDF وحذف التنزيلات وتأكيده (`ReadingProgressRepositoryImplTest.kt`، `PdfReaderViewModelTest.kt`، `DownloadsViewModelTest.kt`، `AppDestinationTest.kt`، `SectionFilesDownloadsRtlTest.kt`، `PdfReaderRtlTest.kt`).

3. **ما شُغّل فعلياً من اختبارات وعمليات تحقق:**
   - **تم تشغيله فعلياً في هذه البيئة:** الفحص الساكن الشامل على الشيفرة والموارد (`grep` / مراجعة الملفات) للتحقق من:
     - تطابق جميع تطبيقات `PdfVersionStorageBroker` مع الواجهة الحقيقية.
     - تطابق جميع توقيعات الواجهات والمستودعات والاختبارات والـ Fakes.
     - عدم وجود اعتماد اختياري/قابل للـ `null` يسمح بتجاوز فحص R2 عند النشر.
     - عدم كسر عزل التنزيلات وتقدم القراءة حسب `(fileId, versionId)` من المرحلة السابعة.
     - تطابق مفاتيح الموارد بين `values/strings.xml` و`values-ar/strings.xml`.
   - **لم يُشغَّل فعلياً:** أي تجميع أو اختبار برمجي أو واجهة (`assembleDebug`, `lintDebug`, `testDebugUnitTest`, `connectedDebugAndroidTest`) لعدم توفر JDK وAndroid SDK وGradle في الحاوية الحالية.

---

## 2. القيود الأمنية والخدمات المعلّقة عمداً

- **قواعد أمان Firestore (`firestore.rules`) غير موجودة في المستودع:** القواعد السابقة كانت ترفض جميع العمليات (`deny-all`) ولم تُنشأ أو تُنشر قواعد جديدة بديلة بعد. يجب اعتماد ونشر `firestore.rules` كمهمة أمنية مستقلة قبل الإطلاق بحيث تفرض صلاحيات `owner`/`admin` وتمنع نشر أو تفعيل أي إصدار على ملف أب أو إصدار مؤرشف أو دون إثبات PDF موثوق من R2.
- **وسيط Cloudflare R2 والخادم الوسيط غير مفعّلين بعد:** يبقى الوسيط في حالة `AWAITING_SECURE_BROKER_INTEGRATION`؛ لا توجد مفاتيح أو روابط R2 وهمية في العميل، ولا يوجد تدفق فعلي مكتمل لرفع ملفات PDF والتحقق الخادمي بعد، ولم يُستخدم Firebase Storage في أي موضع.

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
- `android/app/src/test/java/com/studentlearning/platform2026/data/remote/firebase/FirestoreAdminCatalogMapperTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/AdminCatalogRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/library/SectionFilesViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/admin/AdminPortalViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/downloads/DownloadsViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/navigation/AppDestinationTest.kt`
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
