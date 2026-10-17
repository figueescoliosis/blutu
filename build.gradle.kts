plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Fija la versión de Kotlin que usa el Kotlin integrado de AGP 9 (solo el emisor lo activa).
    alias(libs.plugins.kotlin.compose) apply false
}
