plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.facebeauty"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // MediaPipe memory-maps the model straight out of the APK; it must stay uncompressed.
    androidResources {
        noCompress += "task"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "beauty"
                pom {
                    name.set("FaceBeauty")
                    description.set("Real-time on-device face beautification for Android: skin, reshape, makeup, eyes, filters.")
                }
            }
        }
    }
}

// Pinned model version (float16, ~3.6 MB). Fetched once if it is missing from assets.
val faceModel = file("src/main/assets/face_landmarker.task")
val downloadFaceModel by tasks.registering {
    outputs.file(faceModel)
    onlyIf { !faceModel.exists() }
    doLast {
        faceModel.parentFile.mkdirs()
        uri("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task")
            .toURL().openStream().use { input -> faceModel.outputStream().use { input.copyTo(it) } }
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }

dependencies {
    implementation(libs.mediapipe.tasks.vision)
}
