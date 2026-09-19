import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ─── Release signing ─────────────────────────────────────────────────────────
// All signing material comes from keystore.properties (project root, or app/ as a fallback).
// Nothing is hardcoded and there is deliberately NO fallback password: without the file a
// release build fails with a clear message instead of silently reusing the debug key.
val keystorePropertiesFile = listOf(
    rootProject.file("keystore.properties"),
    file("keystore.properties")
).firstOrNull { it.exists() }

val keystoreProperties = Properties().apply {
    keystorePropertiesFile?.inputStream()?.use { load(it) }
}

fun signingValue(key: String): String? =
    keystoreProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile: File? = signingValue("storeFile")?.let { path ->
    File(path).takeIf { it.isAbsolute } ?: rootProject.file(path)
}
val releaseStorePassword: String? = signingValue("storePassword")
val releaseKeyAlias: String? = signingValue("keyAlias")
val releaseKeyPassword: String? = signingValue("keyPassword")

val hasReleaseSigning = releaseStoreFile?.isFile == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    // Kotlin / manifest package — intentionally UNCHANGED so the whole source tree keeps
    // working. Only the distribution identity (applicationId) below is fork-specific.
    namespace = "com.tiramisu.deepseekwidget"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        // Fork-owned package name: installs side by side with the upstream app.
        applicationId = "dev.randolf.deepseekwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Fail loudly — but only when a release artifact is actually requested, so
// ./gradlew assembleDebug still works on a machine that has no signing material.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { task ->
        val name = task.name
        (name.startsWith("assemble") || name.startsWith("bundle") ||
            name.startsWith("package") || name.startsWith("install")) &&
            name.endsWith("Release")
    }
    if (wantsRelease && !hasReleaseSigning) {
        throw GradleException(
            """
            |Release signing is not configured — refusing to build a release artifact.
            |
            |Create keystore.properties at the project root with:
            |    storeFile=/absolute/path/to/release.jks
            |    storePassword=<your store password>
            |    keyAlias=<your key alias>
            |    keyPassword=<your key password>
            |
            |Then run: ./gradlew assembleRelease
            |Debug builds are unaffected: ./gradlew assembleDebug
            """.trimMargin()
        )
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // AppWidget
    implementation("androidx.glance:glance-appwidget:1.0.0")

    // WorkManager for periodic updates
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Encrypted storage for the account token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Fix: security-crypto 的 Tink 依赖需要 error_prone_annotations
    implementation("com.google.errorprone:error_prone_annotations:2.26.1")

    // JVM unit tests for the pure aggregation / estimation / layout-safety logic
    testImplementation("junit:junit:4.13.2")
}
