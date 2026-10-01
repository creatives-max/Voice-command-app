plugins {
    alias(libs.plugins.voicecontrol.android.library)
    alias(libs.plugins.voicecontrol.android.hilt)
}

android {
    namespace = "com.voicecontrol.core.accessibility"
}

dependencies {
    api(projects.core.screen)
    api(projects.core.engine)
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
}
