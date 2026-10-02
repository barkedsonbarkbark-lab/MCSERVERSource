plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mcserver.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mcserver.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        val renderBaseUrl = providers.gradleProperty("RENDER_BASE_URL").orNull ?: ""
        val renderToken = providers.gradleProperty("RENDER_TUNNEL_TOKEN").orNull ?: ""
        val contactUrl = providers.gradleProperty("MCSERVER_CONTACT_URL").orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "https://play.google.com/store/apps/details?id=com.mcserver.app"
        buildConfigField("String", "RENDER_BASE_URL", "\"$renderBaseUrl\"")
        buildConfigField("String", "RENDER_TUNNEL_TOKEN", "\"$renderToken\"")
        buildConfigField("String", "MCSERVER_USER_AGENT", "\"MCSERVER/0.1.0 ($contactUrl)\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The Android Java runtime is launched as a separate process and loads
        // libc++_shared.so through LD_LIBRARY_PATH, so it must exist on disk.
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation(files("libs/jna-5.14.0.aar"))
    implementation(files("libs/libtailscale.aar"))
}
