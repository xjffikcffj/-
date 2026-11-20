# حقيبتي (Haqibati) — Android Foundation

التأسيس التقني لتطبيق **حقيبتي** التعليمي على منصة Android باستخدام **Kotlin** و**Jetpack Compose** وفق مبادئ **Clean Architecture + MVVM** مع دعم كامل للغة العربية واتجاه **RTL**.

## الهوية التقنية
- **Application ID:** `com.studentlearning.platform2026`
- **اسم التطبيق الظاهر:** `حقيبتي`
- **Min SDK:** 24 | **Target / Compile SDK:** 35
- **JDK:** 17

## مكان إضافة ملف `google-services.json`
عند تسجيل تطبيق Android في مشروع Firebase الموجود:
- **Project ID:** `student-learning-platform-2026`
- **Project Number:** `48673314803`
- **Firestore Database:** `(default)` (`me-central1`)

قم بتنزيل ملف `google-services.json` الحقيقي وضعه في المسار التالي:
```
android/app/google-services.json
```
ملاحظة: ملف `android/app/build.gradle.kts` مجهّز لتفعيل إضافة `com.google.gms.google-services` تلقائياً بمجرد وجود الملف في المسار المذكور أعلاه، دون الحاجة لأي تعديل إضافي.

## أوامر البناء والاختبار (في بيئة Android Studio / Gradle)
```bash
# بناء نسخة Debug
./gradlew assembleDebug

# تشغيل اختبارات الوحدة (JVM Unit Tests)
./gradlew testDebugUnitTest

# تشغيل اختبارات واجهة Compose (على محاكي أو جهاز Android)
./gradlew connectedDebugAndroidTest
```
