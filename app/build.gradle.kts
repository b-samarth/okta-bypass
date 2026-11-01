import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

// Helper: Checks environment variable (CI), then local.properties (local dev), then fallback
fun getSecret(key: String, fallback: String = ""): String {
    return System.getenv(key) ?: localProperties.getProperty(key) ?: fallback
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

        val redisPrimaryEndpoint = getSecret("REDIS_PRIMARY_ENDPOINT")
        val redisPort = getSecret("REDIS_PORT")

        buildConfigField("String", "REDIS_PRIMARY_ENDPOINT", "\"$redisPrimaryEndpoint\"")
        buildConfigField("String", "REDIS_PORT", "\"$redisPort\"")
    }

    signingConfigs {
        create("release") {
            // Priority: CI environment variable path -> root app directory -> local keystore path
            val keystorePath = localProperties.getProperty("KEYSTORE_PATH")
                ?: "${rootProject.rootDir}/app/release.jks"

            storeFile = file(keystorePath)
            storePassword = getSecret("KEYSTORE_PASSWORD")
            keyAlias = getSecret("KEY_ALIAS")
            keyPassword = getSecret("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            // Attach the release signing config
            signingConfig = signingConfigs.getByName("release")

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
    implementation("redis.clients:jedis:5.2.0")

}