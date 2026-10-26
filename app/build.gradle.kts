import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.facebeauty.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.facebeauty.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Phones only need ARM; x86_64 is kept so the sample runs on the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Debug-signed so a release build can be installed for performance testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Lets the library memory-map the face model instead of copying it into RAM.
    androidResources {
        noCompress += "task"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":beauty"))
    implementation(project(":beauty-livekit"))
    implementation(libs.livekit.android)
    implementation(libs.activity)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.guava) // ListenableFuture; already shipped by MediaPipe
}
