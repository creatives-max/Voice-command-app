plugins {
    alias(libs.plugins.voicecontrol.android.application)
    alias(libs.plugins.voicecontrol.android.compose)
    alias(libs.plugins.voicecontrol.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voicecontrol.app"

    defaultConfig {
        applicationId = "com.voicecontrol.app"
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Release signing is injected by CI / the developer via environment variables.
            val storePath = System.getenv("VC_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("VC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VC_KEY_ALIAS")
                keyPassword = System.getenv("VC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("VC_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.accessibility)
    implementation(projects.core.voice)
    implementation(projects.core.network)
    implementation(projects.feature.home)
    implementation(projects.feature.inspector)
    implementation(projects.feature.assistant)
    implementation(projects.feature.auth)
    implementation(projects.feature.flows)
    implementation(projects.feature.history)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
