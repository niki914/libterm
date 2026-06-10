plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

val libtermGroup = "com.niki914"
val libtermVersion = "0.0.1-SNAPSHOT"

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    group = libtermGroup
    version = libtermVersion
}
