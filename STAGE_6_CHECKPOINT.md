# نقطة التوقف — المرحلة السادسة: قارئ PDF (`STAGE_6_CHECKPOINT.md`)

**حالة المرحلة:** مكتملة برمجياً مبدئياً، غير معتمدة تشغيلياً.

---

## 1. ملخص ما تم إنجازه في المرحلة السادسة

- تأسيس بنية قارئ PDF المدمج لتطبيق **حقيبتي** على Android باستخدام `android.graphics.pdf.PdfRenderer` لعرض الصفحات حسب الحاجة، ومكتبة `com.tom_roush:pdfbox-android:2.0.27.0` لاستخراج النصوص والبحث مع تطبيع اللغة العربية (`ArabicTextNormalizer`).
- ربط شاشة قارئ PDF (`PdfReaderScreen` و`PdfReaderViewModel` و`PdfReaderUiState`) بالتنقل (`HaqibatiNavHost` و`AppDestination.PdfReader`) مع دعم:
  - عرض الصفحات المتدرج والتكبير/التصغير (من `1.0x` إلى `3.0x`) والانتقال السريع بين الصفحات.
  - الوضع الليلي للقراءة وشريط البحث النصي داخل الملف مع كشف الملفات المصوّرة الخالية من النص القابل للاستخراج (`NO_EXTRACTABLE_TEXT`).
  - حفظ تقدم القراءة محلياً عبر `DataStore` (`ReadingProgressDataSource`) مرتبطاً حصرياً بمعرف الملف `fileId` ودون ربط بحساب المستخدم أو مسحه عند تسجيل الخروج أو تبديل الحسابات.
  - الحفاظ على التوافق التام مع بنية المرحلة الرابعة (`StudyFileMetadata` و`activeVersionId` كمؤشر للإصدار النشط دون افتراض روابط Cloudflare R2 أو مفاتيح سرية، مع بقاء `brokerEndpointBaseUrl = null`).
- تنفيذ التدقيق التقني النهائي للمرحلة السادسة وإصلاح مشكلات التزامن والذاكرة وتجربة التكبير وإضافة اختبارات الوحدة اللازمة.

---

## 2. أسماء الملفات التي عُدّلت أو أُضيفت خلال التدقيق النهائي

### ملفات الإنتاج والتكوين المعدّلة:
- `android/app/src/main/java/com/studentlearning/platform2026/data/pdf/AndroidPdfDocumentEngine.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/pdf/HttpPdfContentFetcher.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImpl.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModel.kt`
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`
- `android/app/proguard-rules.pro`

### ملفات الاختبارات المعدّلة:
- `android/app/src/test/java/com/studentlearning/platform2026/fakes/FakeRepositories.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModelTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/ReadingProgressRepositoryImplTest.kt`

### ملفات الاختبارات الجديدة المضافة:
- `android/app/src/test/java/com/studentlearning/platform2026/data/repository/PdfDocumentRepositoryImplTest.kt`
- `android/app/src/test/java/com/studentlearning/platform2026/data/pdf/PdfRenderSizingTest.kt`

---

## 3. الإصلاحات التقنية التي نُفذت

1. **إدارة الذاكرة أثناء البحث (`AndroidPdfDocumentEngine.kt`):**
   - التحويل من التحميل الافتراضي في الذاكرة الرئيسية إلى `MemoryUsageSetting.setupMixed(4 MiB).setTempDir(scratchDir)` لترحيل مخازن التدفقات الزائدة إلى مجلد مؤقت معزول تحت `appContext.cacheDir` مع حذفه دائماً داخل `finally` بعد إغلاق `PDDocument`.
   - فحص `closed.get()` مع `coroutineContext.ensureActive()` عند حدود كل صفحة لإيقاف البحث عند إغلاق الجلسة.

2. **سلامة رسم الصفحات والتكبير وحدود Bitmaps (`AndroidPdfDocumentEngine.kt`، `PdfReaderScreen.kt`):**
   - استبدال عدّاد الـ 4 صفحات في `LruCache` بكاش مقيد بحجم الذاكرة الفعلي (`MAX_BITMAP_CACHE_KB = 24 * 1024` عبر `sizeOf`).
   - استخراج `PdfRenderSizing` لفرض سقف عرض (`480..2048px` بكمّية `256px`)، وسقف ارتفاع (`3072px`)، وسقف إجمالي البيكسلات (`2048 * 2048`)، مع التقاط `OutOfMemoryError` لإفراغ الكاش وإعادة المحاولة بنصف الأبعاد.
   - إصلاح انقطاع إيماءة القرص (`detectTransformGestures`) باستخدام `pointerInput(session.fileId)` و`rememberUpdatedState`، وتكميم عرض الرسم في الواجهة مع الاحتفاظ بآخر `Bitmap` صالحة لمنع وميض مؤشر التحميل أثناء التكبير.

3. **إغلاق الجلسة والموارد المؤقتة (`AndroidPdfDocumentEngine.kt`، `HttpPdfContentFetcher.kt`):**
   - تنسيق `close()` في `AndroidPdfDocumentSession` باستخدام عدّاد العمليات الجارية (`activeOperations: AtomicInteger`) وعلم التحرير الوحيد (`resourcesReleased: AtomicBoolean`) لمنع إغلاق `PdfRenderer` / `ParcelFileDescriptor` أو حذف الملف المؤقت أثناء تنفيذ عمليات `IO` دون حظر الخيط الرئيسي.
   - إضافة عدّاد تسلسلي لأسماء الملفات المؤقتة في `HttpPdfContentFetcher` وربط `invokeOnCompletion` لقطع `HttpURLConnection.disconnect()` عند إلغاء التحميل.

4. **منع سباقات حفظ تقدم القراءة (`PdfReaderViewModel.kt`، `ReadingProgressRepositoryImpl.kt`):**
   - إضافة `accessMutex` داخل `ReadingProgressRepositoryImpl` لتسلسل عمليات القراءة والكتابة والحذف.
   - إضافة تتبع تسلسلي لكل ملف (`latestRequestedProgressByFile` و`lastPersistedSequenceByFile`) و`progressWriteMutex` في `PdfReaderViewModel` لمنع كتابة صفحة مؤجلة قديمة بعد حفظ صفحة أحدث.
   - إضافة `flushPendingProgress` باستخدام `CoroutineStart.UNDISPATCHED` و`withContext(NonCancellable)` لضمان حفظ آخر صفحة مرئية قبل الخروج (`onCleared`) أو تبديل الملف (`openFile`) أو إعادة المحاولة (`retry`)، مع حجز `getProgress` خلف `progressWriteMutex`.

5. **تجهيز قواعد R8 / ProGuard (`proguard-rules.pro`):**
   - إضافة قواعد `-keep class com.tom_roush.fontbox.** { *; }` و`-dontwarn com.tom_roush.fontbox.**` و`-dontwarn java.awt.**` لحماية مكونات استخراج النصوص والخطوط عند تفعيل `isMinifyEnabled = true` في بناء `release`.

---

## 4. الاختبارات التي كُتبت ولم تُنفذ

> **تنبيه صريح:** لم يُنفذ أي تجميع أو اختبار في بيئة التشغيل الحالية لعدم توفر `JDK` أو `Android SDK`، وبالتالي فإن الاختبارات التالية **مكتوبة في الكود فقط ولم تُختبر تشغيلياً بعد**:

- **اختبارات `PdfReaderViewModelTest.kt` المضافة:**
  - `overlapping debounced and immediate page saves preserve newest page without regression`: عدم رجوع التقدم لصفحة أقدم عند تداخل الحفظ المؤجل والفوري.
  - `pending debounced page save flushes on ViewModel clear and file switch without mixing files`: تفريغ الحفظ المؤجل عند `ViewModelStore.clear()` وعند التبديل السريع بين ملفين دون خلط تقدم الملفين.
  - `cancelling search mid-execution stops remaining page scans and resets search state`: إلغاء البحث في منتصف التنفيذ وتبديل الملف أثناء البحث.
  - `zoom bounds clamp cleanly between 1x and 3x without negative or excessive scale`: تقييد حدود التكبير بين `1.0x` و`3.0x`.
- **اختبارات `ReadingProgressRepositoryImplTest.kt` (المحدثة والسابقة):**
  - `serialized access keeps last sequential page intact under concurrent saves`: تسلسلية الكتابة عبر `ReadingProgressRepositoryImpl`.
  - بقاء التقدم بعد `signOut` وتبديل الحساب وعدم تأثره بـ `UID` (اختبار موجود مسبقاً في الملف ولم يُنفذ هنا).
- **اختبارات `PdfDocumentRepositoryImplTest.kt` (جديد):**
  - حذف الملفات المؤقتة (`deleteOnClose = true`) عند فشل فتح الجلسة أو إلغائها، والحفاظ على الملفات المحلية الدائمة (`deleteOnClose = false`).
- **اختبارات `PdfRenderSizingTest.kt` (جديد):**
  - التحقق من تكميم العرض (`quantizeWidthPx`) وسقف الأبعاد وإجمالي البيكسلات والنسخة المخفّضة لنصف الأبعاد بعد `OutOfMemoryError`.
- **اختبارات سابقة في المشروع لم تُنفذ في هذه البيئة:**
  - `PdfSourceAndFetcherTest.kt` و`ArabicTextNormalizerTest.kt` و`PdfReaderRtlTest.kt` (اختبار Compose instrumented).

---

## 5. أوامر البناء والاختبار التي يجب تشغيلها لاحقاً

تُنفّذ الأوامر التالية من داخل مجلد `android/` في بيئة تحتوي على **JDK 17** و**Android SDK (compileSdk 35)**:

```bash
# 1. تشغيل جميع اختبارات الوحدة على JVM
./gradlew testDebugUnitTest

# 2. التحقق من بناء نسخة التطوير
./gradlew assembleDebug

# 3. التحقق من بناء نسخة الإصدار وتوافق قواعد ProGuard / R8 لـ PDFBox
./gradlew assembleRelease

# 4. تشغيل فحص Android Lint
./gradlew lintDebug

# 5. تشغيل اختبارات الواجهة RTL على محاكي أو جهاز Android متصل
./gradlew connectedDebugAndroidTest
```

*(ملاحظة: يلزم التحقق اليدوي أيضاً على جهاز/محاكي من البحث النصي والتكبير والتمرير في ملف PDF عربي فعلي عبر مزوّد محلي أو بعد ربط وسيط التوقيع في المراحل اللاحقة.)*

---

## 6. المشكلات والقيود المتبقية

1. **غياب التحقق التشغيلي المحلي:** لا تتوفر بيئة `JDK` / `Android SDK` داخل حاوية التطوير الحالية؛ لذا لم تُتحقق صحة التجميع (`compile`) أو اجتياز اختبارات الوحدة والواجهة فعلياً بعد.
2. **قيود استهلاك الذاكرة في `pdfbox-android`:** حتى مع `MemoryUsageSetting.setupMixed(4 MiB)`، تفرض مكتبة PDFBox تحميل وتحليل شجرة الكائنات وجدول المراجع والخطوط داخل الذاكرة عند `PDDocument.load`؛ ما يعني أن الملفات الضخمة جداً قد تظل ذات استهلاك ملحوظ للذاكرة أثناء البحث، كما أن الإلغاء يتم عند حدود الصفحات وليس أثناء تحليل الصفحة الواحدة.
3. **ملفات الكاش اليتيمة بعد الإغلاق القسري (`SIGKILL`):** إذا أوقف النظام العملية إجبارياً قبل تنفيذ `session.close()`، تبقى ملفات `viewer_*.pdf` في `cacheDir` حتى يفرّغها نظام Android أو تُعالج بسياسة تنظيف زمنية لاحقاً.
4. **عدم ربط مصدر البث الآمن بعد:** ما زال `BrokeredPdfFileSourceDataSource(brokerEndpointBaseUrl = null)` في انتظار تكامل وسيط Cloudflare R2 الموقّع (خارج نطاق المرحلة السادسة).

---

## 7. الحالة النهائية لنقطة التوقف

- **حالة المرحلة السادسة:** **مكتملة برمجياً مبدئياً، غير معتمدة تشغيلياً.**
- **الإجراء التالي المشروط:** تشغيل أوامر البناء والاختبارات المذكورة في القسم (5) في بيئة Android الفعلية واعتماد نتائجها قبل الانتقال إلى المرحلة السابعة.

---

## إصلاح بيانات معاينة الويب

ملاحظة مستقلة توثّق الإصلاح المحدود لنقص المواد الدراسية داخل معاينة الويب (`src/`) فقط، دون المساس بكود Android (`android/`) أو إعدادات وبيانات وقواعد أمان Firebase:

- **استكمال المواد إلى 7 لكل فرع (`src/data/seedCatalog.ts`):** استُكملت مصفوفة `SEED_SUBJECTS` لتضم 7 مواد منشورة للفرع العلمي و7 مواد منشورة للفرع الأدبي وفق الترتيب والأسماء المعتمدة، مع الحفاظ على معرفات وأسماء الفروع (`scientific` و`literary`) وعلى معرفات المواد القديمة المرتبطة بأقسام `SEED_SECTIONS` (`math_sci`، `physics_sci`، `chemistry_sci`، `arabic_lit`، `history_lit`).
- **تحديث مفتاح التخزين المحلي (`src/state/HaqibatiContext.tsx`):** حُدّث `STORAGE_KEYS.CATALOG` إلى `haqibati_catalog_v2` لتجاوز البيانات المخزنة مؤقتاً سابقاً في `localStorage` وضمان تحميل المواد الـ 14 كاملة.
- **الاحتفاظ باختبار التحقق (`src/data/seedCatalog.test.ts`):** أُضيف وثُبّت اختبار يتحقق من وجود 7 مواد لكل فرع بالترتيب الصحيح، وفرادة المعرفات، وسلامة ارتباط `SEED_SECTIONS`.
- **نتائج التحقق الفعلية:**
  - نجاح الحالات الاختبارية الخمسة عبر `./node_modules/.bin/tsx src/data/seedCatalog.test.ts`.
  - نجاح فحص TypeScript (`tsc --noEmit`).
  - نجاح بناء Vite (`vite build`).
- **التحقق في المعاينة:** تم تأكيد ظهور المواد السبع كاملة في كل من الفرع العلمي والفرع الأدبي داخل معاينة الويب.

---

## دفعة إصلاحات ما بعد المراجعة الشاملة

تم تنفيذ دفعة إصلاحات محددة في كل من Android ومعاينة الويب استناداً إلى نتائج المراجعة الشاملة (دون المساس بإعدادات Firebase أو البدء بالمرحلة السابعة أو تعديل تخزين التنزيلات قبل قرار المستخدم):

### 1. الملفات المعدّلة والمضافة

#### تطبيق Android (`android/`):
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfViewportTransform.kt` *(جديد)*
- `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/xml/backup_rules.xml` *(جديد)*
- `android/app/src/main/res/xml/data_extraction_rules.xml` *(جديد)*
- `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfViewportTransformTest.kt` *(جديد)*

#### معاينة الويب (`src/`):
- `src/domain/catalogNavigation.ts` *(جديد)*
- `src/domain/pdfReaderHelpers.ts` *(جديد)*
- `src/screens/PdfReaderScreen.tsx`
- `src/screens/LibraryScreen.tsx`
- `src/App.tsx`
- `src/state/HaqibatiContext.tsx`
- `src/screens/AdminPortalScreen.tsx`
- `src/screens/AuthScreens.tsx`
- `src/domain/fixesVerification.test.ts` *(جديد)*

### 2. ملخص الإصلاحات التقنية المنفّذة
1. **التكبير والسحب وتعارض الإيماءات في `PdfReaderScreen.kt` و`PdfViewportTransform.kt`:**
   - إضافة التتبع الثنائي الأبعاد `PdfViewportOffset(x, y)` (`translationX` و`translationY`) وإعادة ضبط/تقييد الإزاحة تلقائياً عند تغيير التكبير أو أبعاد الشاشة لمنع قصّ أطراف الصفحات عند الحواف.
   - فصل التمرير الطبيعي عند `1x` عن إيماءات التكبير والتحريك: تمرير `userScrollEnabled = !isZoomed` إلى `LazyColumn` وعدم استهلاك لمسة الإصبع الواحد عند `1x`، وتمرير فائض السحب العمودي بعد بلوغ الحافة إلى `listState.dispatchRawDelta` عند التكبير.
2. **منع نقل البيانات المحلية عبر النسخ الاحتياطي (`AndroidManifest.xml`):**
   - ضبط `android:allowBackup="false"` وإضافة `backup_rules.xml` (لـ API < 31) و`data_extraction_rules.xml` (لـ API 31+ لكل من `<cloud-backup>` و`<device-transfer>`) لاستثناء `DataStore` والتفضيلات والبيانات المحلية دون المساس ببقائها على الجهاز نفسه.
3. **تقدم القراءة أثناء التمرير ورسائل البحث في قارئ الويب (`PdfReaderScreen.tsx` و`pdfReaderHelpers.ts`):**
   - ربط `IntersectionObserver` + `onScroll` لتحديث `currentPage` وحفظ التقدم محلياً أثناء التمرير الطبيعي بالفأرة أو اللمس مع حماية لحظة الاستئناف الأولية (`isProgrammaticScrollRef`).
   - إظهار رسائل عربية واضحة لحالتي `NO_EXTRACTABLE_TEXT` و`ERROR` مع التفاف تنفيذ البحث بكتلة `try/catch`.
4. **تنقل الويب بالمعرفات الحيّة (`catalogNavigation.ts`، `LibraryScreen.tsx`، `App.tsx`):**
   - تحويل `LibraryRoute` و`AppScreen.PDF_READER` للاعتماد على المعرفات (`branchId`، `subjectId`، `sectionId`، `fileId`) وقراءة الكائنات الحية من `catalog` مع معالجة واضحة عند التعديل أو إلغاء النشر أو الأرشفة.
5. **عزل وتوضيح محاكاة الدخول التجريبية في الويب (`HaqibatiContext.tsx`، `AdminPortalScreen.tsx`، `AuthScreens.tsx`):**
   - إبقاء حسابات المعاينة المدمجة في الذاكرة فقط وعدم تخزين كلمات مرور نصية في `localStorage` (`haqibati_preview_students_v2` مع مسح `haqibati_users_v1` القديم)، وتوضيح أن بيانات الدخول محاكاة محلية لمعاينة الويب فقط ولا توجد في `android/`.

### 3. حالة الاختبارات
- **اختبارات الويب المنفذة فعلياً (نجحت بالكامل):**
  - `./node_modules/.bin/tsx src/data/seedCatalog.test.ts` -> **5/5 PASS**.
  - `./node_modules/.bin/tsx src/domain/fixesVerification.test.ts` -> **3/3 PASS**.
  - `npm run lint` (`tsc --noEmit`) -> **نجاح بدون أخطاء**.
  - `npm run build` (`vite build`) -> **نجاح بدون أخطاء**.
- **اختبارات Android المكتوبة وغير المنفذة بعد:**
  - أُضيف `PdfViewportTransformTest.kt` (3 حالات اختبارية) إلى جانب اختبارات المرحلة السادسة السابقة (إجمالي 35 ملف اختبار في `android/app/src/test/` و`androidTest/`)، **ولم تُنفّذ ولم يُبنَ APK** لعدم توفر `JDK` أو `Android SDK` في بيئة التشغيل الحالية.

### 4. المشكلات والعناصر المؤجلة بانتظار القرار
1. **التنزيلات المحلية عند تسجيل الخروج وتبديل الحساب (الويب وAndroid):** مُجمّدة بانتظار قرار المستخدم بشأن سلوك الوصول بعد تسجيل الخروج وعزل الحسابات.
2. **التحقق التشغيلي على Android:** لا يزال بناء وتنفيذ اختبارات Android في بيئة JDK 17 + Android SDK شرطاً للاعتماد التشغيلي.
3. **أعمال المراحل اللاحقة:** ملفات Firebase (`google-services.json` و`firestore.rules` و`firestore.indexes.json`)، منظومة الإصدارات (`versions`)، وسيط Cloudflare R2، ووحدة التنزيلات في Android لم تُبدأ التزاماً بنطاق المرحلة.

---

## دفعة الإصلاحات الثانية (استناداً إلى التدقيق التقني المستقل الثاني)

أُجريت إصلاحات مستهدفة للعيوب المؤكدة في التدقيق الثاني دون إعادة تصميم الأجزاء السليمة، ودون تعديل سياسة التنزيلات أو إعدادات Firebase أو R2 أو البدء بالمرحلة السابعة:

### 1. الملفات المعدّلة والجديدة
- **Android (`android/`):**
  - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfViewportTransform.kt`
  - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`
  - `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfViewportTransformTest.kt`
  - `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/PdfReaderRtlTest.kt`
- **معاينة الويب (`src/`):**
  - `src/domain/pdfReaderHelpers.ts`
  - `src/domain/catalogNavigation.ts`
  - `src/screens/PdfReaderScreen.tsx`
  - `src/screens/LibraryScreen.tsx`
  - `src/screens/DownloadsScreen.tsx`
  - `src/App.tsx`
  - `src/state/HaqibatiContext.tsx`
  - `src/screens/AuthScreens.tsx`
  - `src/screens/AdminPortalScreen.tsx`
  - `src/domain/fixesVerification.test.ts`
  - `src/test/minimalDom.ts` *(جديد — للاختبار فقط)*
  - `src/screens/pdfReaderDom.test.tsx` *(جديد — للاختبار فقط)*

### 2. الإصلاحات المنفّذة
1. **قارئ الويب والتمرير الفعلي (`PdfReaderScreen.tsx`، `pdfReaderHelpers.ts`):** استبدال `min-h-screen` بـ `flex h-dvh flex-col overflow-hidden` وتقييد `<main>` بـ `min-h-0 flex-1 overflow-y-auto` لتصبح `<main>` حاوية التمرير الفعلية، مع إزالة `onMouseEnter` ومنع تغيير التقدم بمجرد النقر أو تحريك المؤشر، واستبدال مهلة التمرير القصيرة بالتحقق من وصول الصفحة (`hasReachedTargetPage`) وحدث `scrollend`.
2. **القفز وإيماءات التكبير في Android (`PdfReaderScreen.kt`، `PdfViewportTransform.kt`):**
   - محاذاة `panOffset` عبر `alignmentOffsetAfterPageJump` بعد القفز البرمجي بين الصفحات ونتائج البحث، وتقييد `panOffset` بـ `(uiState.fileId, session)` لإعادة الضبط عند `retry()` أو فتح ملف جديد.
   - تتبّع `gestureZoomScale` محلياً داخل `awaitEachGesture` مع المزامنة مع أزرار الشريط السفلي، ومنع تمرير `LazyColumn` بإصبعين عند التكبير `1x` مع إبقاء التمرير الطبيعي والـ fling بإصبع واحد.
3. **نشر العناصر الأبوية وتوحيد الرجوع (`catalogNavigation.ts`، `DownloadsScreen.tsx`، `LibraryScreen.tsx`، `App.tsx`):** توسيع `resolvePublishedStudyFile` للتحقق من نشر الفرع والمادة والقسم والملف وعدم أرشفته، وتطبيق التحقق في شاشة التنزيلات، وتوحيد الرجوع عبر `resolveLibraryBackRoute`.
4. **حدود محاكاة المصادقة في الويب (`HaqibatiContext.tsx`، `AuthScreens.tsx`، `AdminPortalScreen.tsx`):** قصر `PREVIEW_DEMO_ACCOUNTS` وتلميحات الدخول التجريبية على وضع التطوير والمعاينة (`IS_PREVIEW_DEMO_CREDENTIALS_ENABLED`) مع حجبها عن بناء الاستضافة وتوضيح أن البصمة المحلية `FNV-1a` وحفظ الدور في `localStorage` محاكاة بصرية وليست أماناً حقيقياً.

### 3. حالة الاختبارات المحدّثة
- **اختبارات ويب وDOM منفّذة فعلياً:**
  - `src/data/seedCatalog.test.ts`: **5/5 PASS**.
  - `src/domain/fixesVerification.test.ts`: **5/5 PASS**.
  - `src/screens/pdfReaderDom.test.tsx`: **3/3 PASS DOM** (تمرير `<main>` الفعلي وحفظ التقدم، ثبات التقدم عند تحريك المؤشر، حفظ الصفحة المستأنفة ومنع الكتابة العكسية، وحجب الملفات ذات الأصل غير المنشور في شاشة التنزيلات).
  - فحص TypeScript (`npm run lint`) وبناء الإنتاج (`npm run build`).
- **اختبارات Android مكتوبة فقط ولم تُنفّذ (لعدم توفر JDK / Android SDK):**
  - `PdfViewportTransformTest.kt` (أُضيف اختبارا سحب إصبعين عند `1x` ومحاذاة القفز أثناء التكبير).
  - `PdfReaderRtlTest.kt` (أُضيف اختبار واجهة Compose للقرص عند `1x` والتمرير بإصبع واحد والقفز أثناء التكبير).

---

## دفعة الإصلاحات الثالثة (معالجة ملاحظات التدقيق المستقل الثالث الخمس)

نُفّذت إصلاحات مركّزة للعيوب الخمسة المؤكدة في تقرير التدقيق المستقل الثالث فقط، دون بدء المرحلة السابعة أو تغيير سياسة التنزيلات أو إعدادات Firebase أو R2 أو Firestore Security Rules:

### 1. الملفات المعدّلة والجديدة
- **الويب والبناء (`src/` والجذر):**
  - `src/domain/pdfReaderHelpers.ts`: تشديد `hasReachedTargetPage` بحيث لا يكفي بلوغ نهاية التمرير (`atBottomEdge`) وحده إلا عندما تكون الصفحة المستهدفة مكتملة الظهور داخل نافذة العرض دون قصّ علوي، مع إضافة `isPureHorizontalScroll` لعزل التمرير الأفقي عند التكبير.
  - `src/screens/PdfReaderScreen.tsx`: تثبيت الصفحة الأخيرة القصيرة (`pinnedShortPageRef`) بعد القفز أو الاستئناف حتى يتحرك المستخدم عمودياً للتمرير الطبيعي، ومنع فك القفل عند `scrollend` الأفقي أو المبكر وعند عجلة التمرير الأفقية (`deltaX` فقط)، وإلغاء اعتماد أي صفحة غير مُبلَغ إليها عند انتهاء مهلة التعثّر (`2500ms`) مع توفير مسار استعادة/إلغاء آمن (`stalledTargetPage`)، وإعادة ضبط الأقفال والمؤقتات وحالة التقدم عند تغيّر `file.id` دون إعادة تركيب المكوّن.
  - `src/domain/previewDemoConfig.ts` *(جديد)*: دالة صريحة (`resolvePreviewDemoEnabled`) تقصر حسابات المعاينة على وضع التطوير/المعاينة الاختياري فقط وتحجبها تماماً عن أوامر بناء الإنتاج.
  - `vite.config.ts` و`package.json` و`src/vite-env.d.ts` *(جديد)*: تعريف `__HAQIBATI_PREVIEW_DEMO_ENABLED__` وقت البناء وإلزام `npm run build` بـ `NODE_ENV=production vite build --mode production`، وقصر تفعيل حسابات المعاينة في `npm run dev` على `VITE_ENABLE_PREVIEW_DEMO=true`.
  - `src/state/HaqibatiContext.tsx` و`src/screens/AuthScreens.tsx` و`src/screens/AdminPortalScreen.tsx`: ربط تفعيل حسابات المعاينة وتلميحاتها صراحةً بالثابت المركّب وقت البناء (`__HAQIBATI_PREVIEW_DEMO_ENABLED__`) و`MODE !== 'production'` و`VITE_ENABLE_PREVIEW_DEMO === 'true'` لاستبعادها قطعياً من حزم الإنتاج حتى مع وجود `NODE_ENV=development` في الصدفة.
  - `src/domain/fixesVerification.test.ts` و`src/screens/pdfReaderDom.test.tsx` و`src/test/minimalDom.ts`: توسيع الاختبارات (إضافة `FakeWheelEvent` وتقدّم المؤقتات الحتمي `advanceTimersBy`) لتغطية الصفحة الأخيرة القصيرة (قفز/استئناف/ثبات/تمرير طبيعي)، وتجاهل `scrollend` والتمرير الأفقي، ومهلة التعثّر `2500ms` بدون تلويث `localStorage`، وتبديل `file.id` أثناء القفل، وإخفاء بيانات المعاينة في الإنتاج.
- **Android (`android/`):**
  - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`: ربط `rememberLazyListState` بـ `uiState.currentPage`، وحفظ `lastHandledScrollRequestToken` و`panOffset` عبر `rememberSaveable(uiState.fileId)` لعدم إعادة تنفيذ طلب قفز قديم عند تدوير الشاشة وإعادة تركيب Compose، وإضافة `PDF_VIEWPORT_TEST_TAG = "pdf_pages_viewport"` على حاوية عرض الصفحات الفعلية مع `key(uiState.fileId)`.
  - `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/PdfReaderRtlTest.kt`: توجيه إيماءات `pinch` (تكبيراً وتصغيراً مع التحقق الفعلي من ارتفاع `zoomScale`) و`swipeUp` مباشرة إلى `onNodeWithTag(PDF_VIEWPORT_TEST_TAG)` بدلاً من زر الشريط السفلي، واستبدال التأكيد الشكلي بـ `visiblePage > 1` وتأكيد الصفحة المعروضة، وإضافة اختبار `pdfReader_preservesScrolledPageAndZoomAfterConfigurationRecreation` باستخدام `StateRestorationTester`.

### 2. نتائج التحقق والاختبارات (فصل صريح بين المنفّذ والمكتوب فقط)
- **اختبارات وبناءات الويب المنفّذة فعلياً (ناجحة 100%):**
  - `./node_modules/.bin/tsx src/data/seedCatalog.test.ts`: **5/5 PASS**.
  - `./node_modules/.bin/tsx src/domain/fixesVerification.test.ts`: **8/8 PASS**.
  - `./node_modules/.bin/tsx src/screens/pdfReaderDom.test.tsx`: **6/6 PASS DOM**.
  - `npm run lint` (`tsc --noEmit`) و`compile_applet` / `lint_applet`: **نجاح بدون أخطاء**.
  - **اختبارات البناء وفحص `dist/`:**
    1. البناء الإنتاجي المعتاد (`env -u NODE_ENV -u VITE_ENABLE_PREVIEW_DEMO npm run build`): نجح وأخرج حزمة إنتاجية خالية تماماً من بيانات الدخول التجريبية والتلميحات و`jsxDEV`.
    2. البناء بوجود `NODE_ENV=development VITE_ENABLE_PREVIEW_DEMO=true npm run build`: نجح وتجاوز قيود الصدفة وأنتج `dist/` خالياً تماماً من `Owner1234`/`Admin1234`/`Student1234` ورسائل المعاينة و`jsxDEV`.
    3. تفعيل وضع المعاينة الصريح (`VITE_ENABLE_PREVIEW_DEMO=true vite build --mode preview-demo --outDir /tmp/...`): أكّد استمرار عمل وضع المعاينة التطويري عند طلبه صراحةً.
- **اختبارات Android المكتوبة فقط ولم تُنفّذ أو تُجمّع بعد (لعدم توفر JDK / Android SDK في الحاوية):**
  - `PdfViewportTransformTest.kt` و`PdfReaderRtlTest.kt` (بما فيها اختبارات `PDF_VIEWPORT_TEST_TAG` و`StateRestorationTester` لتدوير الشاشة): **مكتوبة ومحدثة في الكود فقط ولم تُشغّل**.

### 3. ما لا يزال يتطلب جهاز Android حقيقياً أو بيئة Android SDK
1. تشغيل `./gradlew testDebugUnitTest` و`assembleDebug` و`assembleRelease` و`lintDebug` و`connectedDebugAndroidTest` على محاكي أو جهاز Android فعلي للتحقق من تجميع `PdfReaderScreen.kt` واجتياز `PdfReaderRtlTest.kt` (القرص، التمرير، واستعادة التدوير ومقتل العملية).
2. اختبار تجربة اللمس الفعلية (التمرير الأفقي/العمودي عند التكبير وتدوير الجهاز مع لوحة المفاتيح أو شريط البحث) على جهاز Android فعلي بملفات PDF عربية متعددة الأحجام.
3. ما يخص المراحل اللاحقة (`versions`، Cloudflare R2، Firebase Console/`google-services.json`، وسياسة التنزيلات عند تسجيل الخروج) لا يزال خارج نطاق المرحلة السادسة التزاماً بالقيود.

---

## دفعة الإصلاح الرابعة المحدودة للمرحلة السادسة (معالجة عيوب التدقيق المستقل الرابع الأربعة فقط)

التزاماً بنطاق المرحلة السادسة ودون البدء بالمرحلة السابعة أو تعديل Firebase أو R2 أو سياسة التنزيلات، تم إصلاح العيوب الأربعة المؤكدة فقط على النحو التالي:

### 1. الملفات المعدّلة وتفاصيل الإصلاحات
1. **Android – تعارض عداد طلبات القفز بعد مقتل العملية وحفظ حالة القارئ عبر `SavedStateHandle`:**
   - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderUiState.kt`: إضافة `scrollRequestSessionId` لتمييز جلسة `ViewModel` الحية عن القيم المحفوظة من عملية ميتة.
   - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModel.kt`: توليد `instanceScrollSessionId` لكل مثيل `ViewModel`، وإضافة `SavedStateHandle` لحفظ/استعادة `fileId` و`currentPage` و`zoomScale` و`isNightModeEnabled` و`areControlsVisible` و`isSearchBarVisible` و`searchQuery` عند مقتل العملية مع التفريق عن تدوير الشاشة (الذي يبقى فيه `ViewModel` وجلسته حيين)، وتفريغ الصفحة المستعادة فوراً إلى `ReadingProgressRepository` وتنظيفها عند فتح ملف آخر أو `retry()`.
   - `android/app/src/main/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderScreen.kt`: حفظ `lastHandledScrollSessionId` مع `lastHandledScrollRequestToken` عبر `rememberSaveable(uiState.fileId)` وإعادة التقييم داخل `LaunchedEffect(uiState.scrollRequestSessionId, uiState.scrollRequestToken)` بحيث لا تمنع قيمة `lastHandledScrollRequestToken` القديمة طلبات القفز الجديدة (`1, 2, 3...`) بعد مقتل العملية، بينما يحفظ التدوير منع القفز المكرر لأن `scrollRequestSessionId` لا يتغير.
   - `android/app/src/test/java/com/studentlearning/platform2026/presentation/pdfreader/PdfReaderViewModelTest.kt`: إضافة اختبار `surviving ViewModel keeps scroll session across rotation while process death restores SavedStateHandle with fresh session` للتحقق من `ViewModel` + `SavedStateHandle` عبر إنشاء نسخة `ViewModel` جديدة من البيانات المحفوظة (وليس إعادة إنشاء `Activity` أو عملية كاملة).
   - `android/app/src/androidTest/java/com/studentlearning/platform2026/presentation/PdfReaderRtlTest.kt`: إضافة اختبار `pdfReader_afterProcessDeathRecreation_executesJumpsNextPrevSearchAndNewFileDespiteHigherPriorToken` باستخدام `StateRestorationTester` لاستعادة حالة Compose (`rememberSaveable`) على `PdfReaderScreenContent` مع تزويد `PdfReaderUiState` ومُعرّف جلسة جديدين بصورة مصطنعة (لا يُنشئ `PdfReaderViewModel` فعلياً).

2. **الويب – إزالة فخ المحور التاريخي `lastScrollAxisRef` ومنطق `handleScrollEnd` (`src/screens/PdfReaderScreen.tsx`):**
   - استبدال `lastScrollAxisRef` بتتبع معرّف الانتقال النشط (`transition.id`) وحركته الرأسية الفعلية (`startScrollTop`, `hasVerticalMovement`, `pendingHorizontalOnly`) وموضع التقييم الرأسي الأخير (`lastVerticalEvaluatedPosRef`).
   - التحقق من الوصول الرأسي الفعلي (`arrived`) أولاً داخل `handleScrollEnd` حتى ينفك القفل فور انتهاء الانتقال دون انتظار مهلة `2500ms` حتى لو تداخله تحريك أفقي، وتجاهل `scrollend` المتأخر من انتقال سابق أو الناتج عن سحب أفقي قبل تحرك الانتقال الجديد، وتجاهل التمرير الأفقي الحر خارج الانتقالات.

3. **الويب – تثبيت الصفحة الأخيرة القصيرة عند التصغير أو تغيّر حجم النافذة قرب الحافة السفلية (`src/screens/PdfReaderScreen.tsx`):**
   - توسيع `pinnedShortPageRef` بحفظ `maxScrollTopAtArrival` و`zoomScaleAtArrival` مع تثبيت الصفحة الأخيرة عند وصولها لحد التمرير السفلي، وإعادة التثبيت عبر `useEffect([file.id, zoomScale])` ومستمع `resize` عند تقلص `maxScrollTop` وبقاء الصفحة الأخيرة ظاهرة عند الحافة السفلية، مع بقاء فك التثبيت فورياً عند التمرير اليدوي للأعلى أو القفز لصفحة أخرى أو تبديل الملف.

4. **الويب – فصل الصفحة المستهدفة للقفز (`pendingJumpState` / `stalledTargetState`) عن الصفحة المؤكدة المحفوظة (`confirmedPageState`) (`src/screens/PdfReaderScreen.tsx`):**
   - ربط `saveReadingProgress` حصراً بـ `confirmedPageState`؛ فلا يُحفظ أي هدف غير مبلوغ في `localStorage` قبل الوصول الفعلي حتى لو تعثّر القفز أو أُغلق القارئ أثناء التعثّر، وعند الإلغاء أو التعثّر بعد حركة جزئية يُحفظ رقم الصفحة المرئية الفعلية فقط، وعند الضغط على `"متابعة الانتقال"` ووصول القارئ للصفحة المستهدفة تُحفظ حينها ويزول التنبيه.
   - تعقب `latestDisplayPageRef` لضمان دقة النقرات المتتالية السريعة على زر الصفحة التالية/السابقة قبل إعادة التصيير.
   - إضافة 3 حزم اختبارات DOM شاملة في `src/screens/pdfReaderDom.test.tsx` تغطي جميع الحالات الست للتحريك الأفقي/`scrollend`، والحالات الخمس للتصغير والتمرير اليدوي والقفز وتبديل الملف، والحالات الخمس لفصل الحفظ عند التعثّر والإغلاق والإلغاء و`"متابعة الانتقال"`.

### 2. نتائج التحقق والاختبارات (فصل صريح بين المنفّذ والمكتوب فقط)
- **اختبارات وبناءات الويب المنفّذة فعلياً (ناجحة 100%):**
  - `./node_modules/.bin/tsx src/data/seedCatalog.test.ts`: **5/5 PASS**.
  - `./node_modules/.bin/tsx src/domain/fixesVerification.test.ts`: **8/8 PASS**.
  - `./node_modules/.bin/tsx src/screens/pdfReaderDom.test.tsx`: **9/9 PASS DOM**.
  - `npm run lint` (`tsc --noEmit`) و`compile_applet` / `lint_applet`: **نجاح بدون أخطاء**.
  - **اختبارات البناء وفحص `dist/`:**
    1. البناء الإنتاجي (`env -u NODE_ENV -u VITE_ENABLE_PREVIEW_DEMO npm run build`): **نجح** مع خلو `dist/` من أي بيانات معاينة تجريبية (`Owner1234`/`Admin1234`/`Student1234`/تلميحات المعاينة) ومن `jsxDEV`.
    2. البناء الإنتاجي القسري تحت متغيرات التطوير (`NODE_ENV=development VITE_ENABLE_PREVIEW_DEMO=true npm run build`): **نجح** مع خلو الحزمة تماماً من بيانات المعاينة التجريبية ومن `jsxDEV`.
- **اختبارات Android المكتوبة فقط ولم تُنفّذ أو تُجمّع بعد (لعدم توفر JDK / Android SDK في الحاوية):**
  - `PdfReaderViewModelTest.kt` و`PdfReaderRtlTest.kt`: **مكتوبة ومحدّثة في الكود فقط ولم تُشغّل**.

### 3. ما لا يزال يتطلب جهاز Android حقيقياً أو بيئة Android SDK
1. تشغيل `./gradlew testDebugUnitTest` و`assembleDebug` و`assembleRelease` و`lintDebug` و`connectedDebugAndroidTest` على بيئة Android SDK / جهاز فعلي للتحقق من التجميع واجتياز جميع الاختبارات.
2. التحقق الميداني من مقتل العملية (`Don't keep activities` أو إيقاف العملية من ADB أثناء القراءة) ومن استجابة القارئ عند التكبير والتصغير وتدوير الشاشة على جهاز فعلي.

---

## دفعة الإصلاح الخامسة والأخيرة للمرحلة السادسة

### 1. الملفات المعدّلة وتفاصيل الإصلاحات
1. **إصلاح الصفحة الأخيرة القصيرة عند التمرير اليدوي وتغيّر ارتفاع الحاوية (`src/screens/PdfReaderScreen.tsx`):**
   - اكتشاف وصول المستخدم يدوياً إلى نهاية المستند أثناء التمرير العادي عندما يكون عند الحد السفلي (`currentMaxScrollTop - container.scrollTop <= 24`) متجهاً للأسفل أو مستقراً وتكون الصفحة الأخيرة مرئية (`visiblePage === totalPages` أو `hasReachedTargetPage` كامل)، وحفظ تثبيت `pinnedShortPageRef` مع تضمين `clientHeightAtArrival`.
   - منع زحف مرجع التثبيت لأعلى أثناء خطوات الصعود اليدوي الصغيرة (`contentOrZoomChanged == false`) حتى لا ينحبس المستخدم في القاع؛ وفور التمرير للأعلى (`> 24px`) ينفك التثبيت بسلاسة دون إعادة تثبيت في الدورة نفسها (`justReleasedPin`).
   - إعادة تقييم التثبيت عند تغيّر `areControlsVisible` (تغيّر ارتفاع `<main>` عند إظهار/إخفاء شريط الأدوات) وعند تغيّر أبعاد الحاوية عبر `ResizeObserver` إضافةً إلى `window.resize` و`zoomScale`، مع مسحه عند تبديل الملف أو القفز لصفحة أخرى.

2. **تحسين بيئة DOM والاختبارات (`src/test/minimalDom.ts` و`src/screens/pdfReaderDom.test.tsx`):**
   - دعم `window.addEventListener`/`removeEventListener`/`dispatchEvent` (`dispatchWindowResize`)، و`ResizeObserver` (`FakeResizeObserver`)، ومُعرّف `value` و`type` على `FakeElement` مع `oninput`/`onchange` و`setFakeInputValue`، وتهيئة DOM الافتراضية قبل استيراد `react-dom/client` ليعمل مسار `input` الحقيقي لـ React 19 دون تعديل التبعيات.
   - تحديث اختبار التعثر لاستخدام حوار الانتقال الرقمي الحقيقي (`<input>` + زر `"انتقال"`) وإضافة اختبار مخصص للتحقق من صحة إدخال حوار الانتقال الرقمي، واختبار مخصص للوصول اليدوي إلى الصفحة الأخيرة القصيرة متبوعاً بالتصغير وتغيّر حجم الحاوية/`window.resize` وتبديل شريط الأدوات والصعود اليدوي البطيء ثم التبديل إلى ملف آخر.

3. **توضيح حدود اختبارات Android (`PdfReaderRtlTest.kt` و`PdfReaderViewModelTest.kt`):**
   - توثيق حدود اختبار `PdfReaderRtlTest.kt` (`pdfReader_afterProcessDeathRecreation...`): يختبر استعادة حالة Compose (`rememberSaveable`) عبر `StateRestorationTester` على `PdfReaderScreenContent` مع تزويد `PdfReaderUiState` ومعرّف جلسة جديدين بصورة مصطنعة، ولا يُنشئ `PdfReaderViewModel` ولا يمرر `SavedStateHandle` عبر النظام.
   - توثيق حدود اختبار `PdfReaderViewModelTest.kt`: يتحقق من منطق استعادة `SavedStateHandle` عبر إنشاء نسخة `PdfReaderViewModel` ثانية ببيانات مستعادة في اختبارات وحدة JVM، وليس اختباراً كاملاً لإعادة إنشاء `Activity` أو مقتل العملية عبر النظام.

### 2. نتائج أوامر التحقق والاختبارات المنفّذة فعلياً (بعد الدفعة الخامسة)
- `./node_modules/.bin/tsx src/data/seedCatalog.test.ts`: **5 ناجحة / 0 فاشلة**.
- `./node_modules/.bin/tsx src/domain/fixesVerification.test.ts`: **8 ناجحة / 0 فاشلة**.
- `./node_modules/.bin/tsx src/screens/pdfReaderDom.test.tsx`: **11 ناجحة / 0 فاشلة**.
- `npm run lint` (`tsc --noEmit`): **ناجح (0 أخطاء)**.
- `npm run build` (`NODE_ENV=production vite build --mode production`): **ناجح** (توليد `dist/index.html` وحزم `dist/assets/`).
- **اختبارات Android (`PdfViewportTransformTest.kt`، `PdfReaderViewModelTest.kt`، `PdfReaderRtlTest.kt`):** **مكتوبة فقط** ولم تُجمّع أو تُشغّل لعدم توفر JDK / Android SDK في الحاوية.

### 3. ما لا يزال يتطلب بيئة Android فعلية
1. تشغيل `./gradlew testDebugUnitTest` و`assembleDebug` و`assembleRelease` و`lintDebug` و`connectedDebugAndroidTest` في بيئة تحتوي على JDK وAndroid SDK.
2. إجراء اختبار تكاملي/ميداني على `Activity` و`NavHost` حقيقية (عبر `Don't keep activities` أو `adb shell am kill`) للتحقق الكامل من إعادة إنشاء `Activity` + `ViewModelProvider` + `SavedStateHandle` وتكبير/سحب/تدوير قارئ PDF على جهاز فعلي.

---

## Final Stage 6 Status — Code Complete

1. **الاكتمال البرمجي:** المرحلة السادسة مكتملة برمجياً (**Code Complete**) على مستوى كود Android والويب، وفق التدقيق النهائي الأخير بعد دفعة الإصلاح الخامسة.
2. **حالة الدفعات البرمجية:** لا توجد دفعة إصلاح برمجية إضافية مطلوبة حالياً.
3. **نتائج اختبارات وبناء الويب الأخيرة:**
   - `seedCatalog`: **5/5**.
   - `fixesVerification`: **8/8**.
   - `pdfReaderDom`: **11/11**.
   - `npm run lint`: **ناجح**.
   - `npm run build`: **ناجح**.
4. **حالة Android الحالية:** اختبارات Android لم تُشغّل ولم يُبنَ APK حتى الآن؛ ولا تزال بانتظار توفر بيئة JDK وAndroid SDK.
5. **حدود تغطية استعادة العملية في Android:** اختبار تكامل `Activity` / `NavHost` / `ViewModel` / `SavedStateHandle` بعد مقتل العملية لم يُنفّذ فعلياً، ويجب عدم وصف اختبارات Compose الحالية (`StateRestorationTester` على `PdfReaderScreenContent`) بأنها تثبت ذلك.
6. **أوامر Android المطلوب تنفيذها مستقبلاً في بيئة JDK وAndroid SDK:**
   - `./gradlew testDebugUnitTest`
   - `./gradlew assembleDebug`
   - `./gradlew assembleRelease`
   - `./gradlew lintDebug`
   - `./gradlew connectedDebugAndroidTest`
7. **الاعتماد التشغيلي:** سيظل اعتماد المرحلة السادسة تشغيلياً معلقاً حتى نجاح البناء والأوامر المذكورة والفحص الفعلي على جهاز أو محاكي Android حقيقي.
