plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.auth"
}

dependencies {
    implementation(projects.core.data)
}
