plugins {
    alias(libs.plugins.voicecontrol.android.library)
    alias(libs.plugins.voicecontrol.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voicecontrol.core.data"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(projects.core.model)
    api(projects.core.engine)
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
