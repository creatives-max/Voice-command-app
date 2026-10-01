plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.home"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.accessibility)
}
