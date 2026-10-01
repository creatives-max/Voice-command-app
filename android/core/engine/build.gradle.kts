plugins {
    alias(libs.plugins.voicecontrol.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.nlp)
    api(projects.core.screen)
}
