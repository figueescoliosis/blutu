import com.android.build.api.artifact.SingleArtifact
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cl.parlantey.receptor"
    compileSdk = 37

    // Sin fuentes Kotlin: sin compilación Kotlin ni kotlin-stdlib (corre en Android 2.3).
    enableKotlin = false

    defaultConfig {
        applicationId = "cl.parlantey.receptor"
        minSdk = 9
        // El Galaxy Y corre 2.3.x (API 10). En 2.3 un targetSdk mayor no cambia nada y, con
        // targetSdk >= 31, lint exigiría BLUETOOTH_CONNECT y permisos en runtime, que no
        // existen en API 9. La app no se publica en Play.
        targetSdk = 10
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        getByName("debug") {
            // Android 2.3 solo entiende la firma v1 (JAR, digests SHA-1).
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        error += listOf("NewApi", "InlinedApi")
        // targetSdk 10 es deliberado (ver defaultConfig).
        disable += listOf("OldTargetApi", "ExpiredTargetSdkVersion")
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
}

/**
 * Comprueba que el APK sirve en Android 2.3: firma v1 válida para minSdk (apksigner),
 * minSdkVersion en el manifiesto (aapt2), dex versión 035 y ninguna referencia a Kotlin ni AndroidX.
 */
abstract class VerificarApk : DefaultTask() {

    @get:InputFiles
    abstract val carpetaApk: DirectoryProperty

    @get:Input
    abstract val buildTools: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun verificar() {
        val apk = carpetaApk.get().asFile.listFiles { f -> f.name.endsWith(".apk") }
            ?.singleOrNull() ?: throw GradleException("no hay un único APK en ${carpetaApk.get()}")
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val herramientas = File(buildTools.get())
        val apksigner = File(herramientas, if (windows) "apksigner.bat" else "apksigner")
        val aapt2 = File(herramientas, if (windows) "aapt2.exe" else "aapt2")
        for (f in listOf(apksigner, aapt2)) {
            if (!f.exists()) throw GradleException("falta $f (instala build-tools ${herramientas.name} con el SDK Manager)")
        }

        val firma = ejecutar(apksigner.path, "verify", "--min-sdk-version", minSdk.get().toString(), "--verbose", apk.path)
        logger.lifecycle(firma.trim())
        if (!firma.lineSequence().any { it.trim() == "Verified using v1 scheme (JAR signing): true" }) {
            throw GradleException("el APK no tiene una firma v1 válida")
        }

        val badging = ejecutar(aapt2.path, "dump", "badging", apk.path)
        val lineaMinSdk = "minSdkVersion:'${minSdk.get()}'"
        if (!badging.lineSequence().any { it.trim() == lineaMinSdk }) {
            throw GradleException("el manifiesto no declara $lineaMinSdk")
        }

        ZipFile(apk).use { zip ->
            val nombres = zip.entries().asSequence().map { it.name }.toList()
            val dex = nombres.filter { Regex("classes\\d*\\.dex").matches(it) }
            if (dex.isEmpty()) throw GradleException("el APK no tiene classes.dex")
            for (nombre in dex) {
                val bytes = zip.getInputStream(zip.getEntry(nombre)).use { it.readBytes() }
                if (!bytes.copyOf(8).contentEquals("dex\n035\u0000".toByteArray(Charsets.US_ASCII))) {
                    throw GradleException("$nombre no es dex 035 (Dalvik de Android 2.3 no lo carga)")
                }
                // Los descriptores de tipo ("Lkotlin/...;") están en la tabla de strings del dex.
                val texto = String(bytes, Charsets.ISO_8859_1)
                for (prohibido in listOf("Lkotlin/", "Lkotlinx/", "Landroidx/")) {
                    if (texto.contains(prohibido)) throw GradleException("$nombre referencia $prohibido")
                }
            }
            val kotlin = nombres.filter { it.startsWith("kotlin/") || it.endsWith(".kotlin_module") }
            if (kotlin.isNotEmpty()) throw GradleException("el APK trae Kotlin: ${kotlin.take(3)}")
            logger.lifecycle("$lineaMinSdk · dex 035 (${dex.joinToString()}) · sin Kotlin ni AndroidX · ${apk.length()} bytes")
        }
        logger.lifecycle("OK: ${apk.name} apto para Android 2.3 (API ${minSdk.get()})")
    }

    private fun ejecutar(vararg comando: String): String {
        val salida = ByteArrayOutputStream()
        val resultado = exec.exec {
            commandLine(*comando)
            standardOutput = salida
            errorOutput = salida
            isIgnoreExitValue = true
        }
        val texto = salida.toString(Charsets.UTF_8)
        if (resultado.exitValue != 0) {
            throw GradleException("${File(comando[0]).name} terminó con código ${resultado.exitValue}:\n$texto")
        }
        return texto
    }
}

val verificarApk = tasks.register<VerificarApk>("verificarApk") {
    group = "verification"
    description = "Verifica que el APK debug del receptor instala en Android 2.3 (firma v1, minSdk, dex 035)."
    buildTools.set(
        androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}").asFile.path }
    )
    minSdk.set(android.defaultConfig.minSdk ?: 9)
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        verificarApk.configure { carpetaApk.set(variant.artifacts.get(SingleArtifact.APK)) }
    }
}

// javac 21 avisa que el nivel 8 es obsoleto: es deliberado (Android 2.3).
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:deprecation"))
}
