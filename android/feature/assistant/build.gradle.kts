plugins {
    alias(libs.plugins.voicecontrol.android.feature)
}

android {
    namespace = "com.voicecontrol.feature.assistant"
}

dependencies {
    implementation(projects.core.accessibility)
    implementation(projects.core.voice)
    implementation(projects.core.engine)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)
}
