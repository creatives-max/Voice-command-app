plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.inspector"
}

dependencies {
    implementation(projects.core.accessibility)
    implementation(projects.core.screen)
}
