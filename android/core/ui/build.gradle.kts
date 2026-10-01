plugins {
    alias(libs.plugins.voicecontrol.android.library)
    alias(libs.plugins.voicecontrol.android.compose)
}

android {
    namespace = "com.voicecontrol.core.ui"
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.androidx.core.ktx)
}
