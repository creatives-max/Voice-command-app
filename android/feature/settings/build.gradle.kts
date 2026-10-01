plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.settings"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.voice)
    implementation(libs.androidx.activity.compose)
}
