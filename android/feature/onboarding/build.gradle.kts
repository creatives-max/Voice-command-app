plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.onboarding"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.accessibility)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
