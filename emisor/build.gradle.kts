plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cl.parlantey.emisor"
    compileSdk = 37

    defaultConfig {
        applicationId = "cl.parlantey.emisor"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.2"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        getByName("debug") {
            // Clave de depuración compartida (firma/LEEME.md).
            storeFile = rootProject.file("firma/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
