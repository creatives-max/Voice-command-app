plugins {
    alias(libs.plugins.voicecontrol.android.library)
    alias(libs.plugins.voicecontrol.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voicecontrol.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        // 10.0.2.2 is the host machine when running in the Android emulator.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://10.0.2.2:8080\"")
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.client.mock)
}
