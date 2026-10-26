plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Maven coordinates. JitPack provides GROUP (com.github.<user>), ARTIFACT (<repo>) and VERSION
// (<tag>), giving com.github.<user>.<repo>:<module>:<tag>. Locally gradle.properties is used.
val jitpackGroup = System.getenv("GROUP")?.let { g -> System.getenv("ARTIFACT")?.let { "$g.$it" } ?: g }
allprojects {
    group = jitpackGroup ?: (findProperty("LIB_GROUP") as String)
    version = System.getenv("VERSION") ?: (findProperty("LIB_VERSION") as String)
}
