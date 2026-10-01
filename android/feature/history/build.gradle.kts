plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.history"
}

dependencies {
    implementation(projects.core.data)
}
