plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
