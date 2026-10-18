import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.local.accessibilitydiagnostics"
    compileSdk = 36
    
    buildFeatures {
            buildConfig = true
        }

    defaultConfig {
        applicationId = "dev.local.accessibilitydiagnostics"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val supabaseUrl = localProperties.getProperty("SUPABASE_URL") ?: ""
        val supabaseAnonKey = localProperties.getProperty("SUPABASE_ANON_KEY") ?: ""
        val upsertEndpoint = localProperties.getProperty("UPSERT_ENDPOINT") ?: ""
        buildConfigField("String", "SUPABASE_URL", supabaseUrl)
        buildConfigField("String", "SUPABASE_ANON_KEY", supabaseAnonKey)
        buildConfigField("String", "UPSERT_ENDPOINT", upsertEndpoint)
    }

    buildTypes {
        release {
            // Shrink, optimize, and obfuscate app code.
            isMinifyEnabled = true

            // Remove unused Android resources.
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
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
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Optional Google Sheets / OAuth dependencies:
    // implementation("com.google.android.gms:play-services-auth:21.2.0")
    // implementation("com.google.api-client:google-api-client-android:2.6.0")
    // implementation("com.google.apis:google-api-services-sheets:v4-rev20240416-2.0.0")
}
