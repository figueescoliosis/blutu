plugins {
    java
    application
}

// Emisor para Windows (PC): Java 21 + JNA. Compila las fuentes del core directamente (el core es una
// biblioteca de Android, pero su código es Java puro): una sola implementación del protocolo.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        java.srcDir("../core/src/main/java")
    }
}

dependencies {
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
}

application {
    mainClass.set("cl.parlantey.pc.Principal")
    applicationName = "ParlanteY"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
