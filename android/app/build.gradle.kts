import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.studentlearning.platform2026"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studentlearning.platform2026"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val configuredBackendBaseUrl = (
            providers.gradleProperty("HAQIBATI_BACKEND_BASE_URL").orNull
                ?: providers.environmentVariable("HAQIBATI_BACKEND_BASE_URL").orNull
                ?: ""
            ).trim()
        val escapedBackendBaseUrl = configuredBackendBaseUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField(
            "String",
            "HAQIBATI_BACKEND_BASE_URL",
            "\"$escapedBackendBaseUrl\""
        )
    }

    val signingProperties = Properties()
    val signingPropertiesFile = rootProject.file("signing.properties")
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { signingProperties.load(it) }
    }

    fun resolveSigningValue(propKey: String, envKey: String): String? {
        val fromFile = signingProperties.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }
        val fromGradle = providers.gradleProperty(envKey).orNull?.trim()?.takeIf { it.isNotEmpty() }
        val fromEnv = providers.environmentVariable(envKey).orNull?.trim()?.takeIf { it.isNotEmpty() }
        return fromFile ?: fromGradle ?: fromEnv
    }

    val releaseStoreFilePath = resolveSigningValue("storeFile", "HAQIBATI_RELEASE_STORE_FILE")
    val releaseStorePassword = resolveSigningValue("storePassword", "HAQIBATI_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = resolveSigningValue("keyAlias", "HAQIBATI_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = resolveSigningValue("keyPassword", "HAQIBATI_RELEASE_KEY_PASSWORD")
    val resolvedReleaseStoreFile = releaseStoreFilePath
        ?.let { path -> rootProject.file(path).takeIf { it.isFile } ?: file(path).takeIf { it.isFile } }
    val hasExternalReleaseSigning = resolvedReleaseStoreFile != null &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

    if (hasExternalReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = resolvedReleaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Fail-closed: never fall back to the debug keystore for release builds.
            if (hasExternalReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // Expose Guava 32.1.3-android (already in runtime classpath via Firebase) on compile classpath
    // because listenablefuture:9999.0-empty-to-avoid-conflict-with-guava hides ListenableFuture otherwise.
    implementation(libs.guava)

    implementation(libs.kotlinx.coroutines.android)

    // Firebase BoM & core SDKs prepared for project: student-learning-platform-2026
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    // Embedded PDF text extraction & search support (paired with Android PdfRenderer)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Apply the Google Services plugin only once android/app/google-services.json is placed.
// Do NOT create a placeholder google-services.json file; download the official file
// from Firebase Console for project "student-learning-platform-2026" (48673314803).
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
} else {
    logger.lifecycle(
        "Notice: android/app/google-services.json is not present yet. " +
            "Google Services plugin will activate automatically once the file is added."
    )
}
