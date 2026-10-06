plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.local.accessibilitydiagnostics"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.local.accessibilitydiagnostics"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Essential: Prevents duplicate META-INF conflicts caused by Google API client libraries
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // -------------------------------------------------------------------------
    // Option A: Dependencies for Google Apps Script Web App (RECOMMENDED)
    // -------------------------------------------------------------------------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // -------------------------------------------------------------------------
    // Option B: Dependencies for direct Google Sheets API v4 (OAuth2)
    // (Uncomment these ONLY if you are using Google Cloud OAuth2 authentication)
    // -------------------------------------------------------------------------
    // implementation("com.google.android.gms:play-services-auth:21.2.0")
    // implementation("com.google.api-client:google-api-client-android:2.6.0")
    // implementation("com.google.apis:google-api-services-sheets:v4-rev20240416-2.0.0")
}