plugins {
    alias(libs.plugins.voicecontrol.android.library)
    alias(libs.plugins.voicecontrol.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voicecontrol.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        // The hosted servers by default; for a local backend in the emulator build with
        // -Pvc.backendUrl=http://10.0.2.2:8080 -Pvc.dashboardUrl=http://10.0.2.2:3000 (or change it in Settings).
        val backendUrl = providers.gradleProperty("vc.backendUrl").getOrElse("https://voicecontrol-backend.onrender.com")
        val dashboardUrl = providers.gradleProperty("vc.dashboardUrl").getOrElse("https://voicecontrol-dashboard.onrender.com")
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "DEFAULT_DASHBOARD_URL", "\"$dashboardUrl\"")
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
