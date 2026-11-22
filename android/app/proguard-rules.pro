# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools/proguard/proguard-android.txt.

# Entry points & background workers/services referenced via AndroidManifest / reflection
-keep class com.studentlearning.platform2026.HaqibatiApplication { *; }
-keep class com.studentlearning.platform2026.MainActivity { *; }
-keep class com.studentlearning.platform2026.data.download.PdfDownloadWorker { *; }
-keep class com.studentlearning.platform2026.data.notifications.HaqibatiFirebaseMessagingService { *; }
-keep class androidx.startup.InitializationProvider { *; }

# PdfBox-Android & FontBox runtime resources/reflection
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
