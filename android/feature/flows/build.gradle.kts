plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.flows"
}

dependencies {
    implementation(projects.core.data)
    implementation(libs.androidx.activity.compose)
}
