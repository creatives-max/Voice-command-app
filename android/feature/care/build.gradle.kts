plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.care"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.network)
}
