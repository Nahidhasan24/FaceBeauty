plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.facebeauty.livekit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":beauty"))
    // Provided by the app, so the app's LiveKit version is the one used.
    compileOnly(libs.livekit.android)
    // BeautyLiveKit calls LiveKit's suspend functions; coroutines come with LiveKit at runtime.
    compileOnly(libs.kotlinx.coroutines.android)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "beauty-livekit"
                pom {
                    name.set("FaceBeauty for LiveKit")
                    description.set("LiveKit VideoProcessor and camera capturer for FaceBeauty.")
                }
            }
        }
    }
}
