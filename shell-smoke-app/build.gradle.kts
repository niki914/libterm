plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.niki914.libterm.shellsmoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niki914.libterm.shellsmoke"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":libterm-core"))
    implementation(project(":libterm-backend-libsu"))
    implementation(project(":libterm-backend-shizuku"))
    implementation(project(":libterm-runtime"))
    implementation(libs.kotlinx.coroutines.android)
}
