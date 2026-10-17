plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cl.parlantey.core"
    compileSdk = 37

    // Sin fuentes Kotlin: sin compilación Kotlin ni kotlin-stdlib (corre en Android 2.3).
    enableKotlin = false

    defaultConfig {
        minSdk = 9
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        error += listOf("NewApi", "InlinedApi")
        abortOnError = true
    }

    testOptions {
        unitTests.all {
            // Intérprete: sin el escape analysis del JIT, SinAsignacionesTest ve las asignaciones
            // que Dalvik sí haría.
            it.jvmArgs("-Xint")
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}

// javac 21 avisa que el nivel 8 es obsoleto: es deliberado (Android 2.3).
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:deprecation"))
}
