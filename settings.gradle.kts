pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiveKit Android SDK pulls in a forked audioswitch build published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FaceBeauty"
include(":beauty")
include(":beauty-livekit")
include(":app")
