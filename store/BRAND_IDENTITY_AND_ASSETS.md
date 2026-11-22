# الهوية البصرية والأصول الرسومية — تطبيق «حقيبتي» (مؤقت)

**الحالة:** مسودة جاهزة للمراجعة والاعتماد — الاسم **«حقيبتي»** اسم مؤقت لحين موافقة المالك النهائية، ومعرّف الحزمة ثابت لا يتغير: `com.studentlearning.platform2026`.

---

## 1. الاسم المؤقت والرسالة

- **الاسم المؤقت في التطبيق:** حقيبتي
- **الشعار النصي داخل التطبيق (`app_tagline`):** مساحتك التعليمية المنظمة
- **الاتجاه واللغة الأساسية:** العربية الكاملة من اليمين إلى اليسار (`RTL` — `ar`) عبر `ArabicLocaleHelper` و`locales_config.xml` و`supportsRtl="true"`.
- **ملاحظة الاعتماد:** أي تعديل لاحق على الاسم النهائي ينحصر في موارد النصوص (`strings.xml`) وعنوان المتجر، دون تغيير `applicationId` (`com.studentlearning.platform2026`).

---

## 2. لوحة الألوان المعتمدة (`Color.kt` + `colors.xml`)

| الدور البصري | الرمز في الشيفرة | القيمة الست عشرية | الاستخدام الفعلي |
|---|---|---|---|
| الأخضر الزمردي الأساسي | `EmeraldPrimary` (`Color.kt`) / `@color/haqibati_emerald_primary` (`colors.xml`) | `#0E5A4F` | خلفية الأيقونة، شاشة البداية النظامية، لون الإشعارات، والعناصر الأساسية |
| الزمردي الأساسي الداكن (Dark Mode) | `EmeraldPrimaryDark` (`Color.kt`) | `#5CD4BE` | العناصر الأساسية في الوضع الداكن |
| الكهرماني الثانوي | `AmberSecondary` (`Color.kt`) / `#D99B26` (`ic_launcher_foreground.xml`) | `#B57B14` / `#D99B26` | الشريط المميز للحقيبة، شارات التحديث والإبراز |
| الرمادي المزرق المكمل | `SlateTertiary` (`Color.kt`) | `#355966` | تدرج شاشة البداية والعناصر المكملة |
| خلفية الورق الدافئ (Light) | `ParchmentBackground` (`Color.kt`) / `#FBF8F1` (`ic_launcher_foreground.xml`) | `#FBF8F1` | خلفية الشاشات الفاتحة ولون رمز الحقيبة في الأيقونة |
| خلفية الغابة الداكنة (Dark) | `DeepForestBackgroundDark` (`Color.kt`) | `#0F1715` | خلفية الشاشات في الوضع الداكن |

---

## 3. نظام أيقونات التطبيق والإشعارات وشاشة البداية

### أ) الأيقونة التكيفية لـ Android 8.0+ (`API 26+`)
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- **الطبقات:**
  - الخلفية: `@drawable/ic_launcher_background` (`#0E5A4F`)
  - المقدمة: `@drawable/ic_launcher_foreground` (حقيبة دراسية باللون `#FBF8F1` مع شريط `#D99B26` ضمن المنطقة الآمنة لـ `108dp × 108dp`)
  - الأيقونة الأحادية (Android 13+ Material You): `@drawable/ic_launcher_monochrome` (`fillType="evenOdd"`)

### ب) الأيقونات الكلاسيكية لإصدارات Android 7.0 – 7.1 (`API 24–25`, لأن `minSdk = 24`)
تم توليد ملفات `PNG32` لكل كثافة شاشة لكلٍّ من `ic_launcher.png` و`ic_launcher_round.png`:
- `mipmap-mdpi`: `48 × 48` بكسل
- `mipmap-hdpi`: `72 × 72` بكسل
- `mipmap-xhdpi`: `96 × 96` بكسل
- `mipmap-xxhdpi`: `144 × 144` بكسل
- `mipmap-xxxhdpi`: `192 × 192` بكسل

### ج) أيقونة متجر Google Play (عالية الدقة)
- **المسار:** `store/assets/ic_launcher_playstore_512.png`
- **المواصفات:** `512 × 512` بكسل، `PNG` (24-bit RGB بدون قناة شفافية)، خلفية كاملة `#0E5A4F` مطابقة تماماً لشروط Google Play Console (بحد أقصى أقل من 1 MB — الحجم الفعلي ~7.4 KB).

### د) أيقونة شريط الحالة والإشعارات (`Status Bar & FCM`)
- **المسار:** `android/app/src/main/res/drawable/ic_stat_haqibati.xml`
- **المواصفات:** متجه أحادي أبيض نقي (`#FFFFFF` فقط على خلفية شفافة، `24dp × 24dp`) متوافق مع إرشادات Android وFCM، مربوط عبر:
  - `NotificationChannelHelper.kt` (`R.drawable.ic_stat_haqibati` ولون التظليل `@color/haqibati_emerald_primary`)
  - `AndroidManifest.xml` عبر مفاتيح `com.google.firebase.messaging.default_notification_icon` و`default_notification_color` و`default_notification_channel_id`.

### هـ) شاشة البداية (Cold Start System Splash + Compose SplashScreen)
- `android/app/src/main/res/drawable/splash_window_background.xml` + `values/themes.xml` (`API 24–30`)
- `android/app/src/main/res/values-v31/themes.xml` (`API 31+` عبر `windowSplashScreenBackground` و`windowSplashScreenAnimatedIcon`)
- `SplashScreen.kt`: موحّد مع شعار الحقيبة `@drawable/ic_launcher_foreground` لمنع وميض الشاشة البيضاء عند التشغيل البارد مع الحفاظ على الاتجاه العربي RTL.

---

## 4. مواصفات أصول Google Play المؤجلة لحين التشغيل على جهاز فعلي

> تنبيه مهم: لم تُلتقط لقطات شاشة حقيقية للتطبيق داخل هذا التقرير لأن بيئة العمل الحالية لا تحتوي على جهاز Android أو محاكي مع `/dev/kvm` ولا يتوفر `google-services.json` أو خادم سحابي منشور. يُمنع تلفيق لقطات شاشة مصطنعة.

عند توفر جهاز فعلي وبيئة مرتبطة، تُجهّز الأصول التالية في `store/assets/`:

1. **لقطات شاشة الهاتف (`Phone Screenshots` — مطلوب من 2 إلى 8 لقطات):**
   - الصيغة: `PNG` أو `JPEG` (24-bit، بدون شفافية)
   - الأبعاد الموصى بها: `1080 × 1920` أو `1080 × 2400` (نسبة عرض إلى ارتفاع `9:16` أو `9:20`)
   - اللقطات المقترح التقاطها من التطبيق الحقيقي:
     1. الشاشة الرئيسية (`HomeScreen`) بالوضع الفاتح واتجاه RTL.
     2. المكتبة — الفروع والمواد والأقسام (`Library / Subjects / Sections`).
     3. قائمة ملفات القسم مع شارة «إصدار جديد» وزر المفضلة و«ترتيب ملفاتي».
     4. عارض PDF العربي المدمج (`PdfReaderScreen`) مع شريط البحث العربي والوضع الليلي.
     5. شاشة التنزيلات المحلية (`DownloadsScreen`) في الوضع الداكن أو الفاتح.
     6. سجل الإشعارات (`NotificationsScreen`) أو الإعدادات.
2. **صورة العرض المميّز (`Feature Graphic` — مطلوبة في Google Play Console):**
   - المسار المقترح بعد التصميم النهائي للاسم: `store/assets/feature_graphic_1024x500.png`
   - الأبعاد الإلزامية: **`1024 × 500` بكسل** (`PNG` أو `JPEG`، بدون شفافية، بهامش أمان 10% حول الحواف).
   - تصميم مقترح عند اعتماد الاسم النهائي: خلفية `#0E5A4F` بتدرج هادئ نحو `#355966` مع شعار الحقيبة والاسم المعتمد ورسالة مختصرة بالعربية.
3. **لقطات الأجهزة اللوحية (اختيارية في حال استهداف التابلت):** `7-inch` و`10-inch` بأبعاد `1200 × 1920` أو `1600 × 2560`.
