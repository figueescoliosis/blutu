package cl.parlantey.emisor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.parlantey.core.Protocolo
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que solo sabe la UI: permisos, Bluetooth, vinculados y lo elegido. */
data class Local(
    val btDisponible: Boolean = true,
    val permisoOk: Boolean = false,
    val btEncendido: Boolean = false,
    val vinculados: List<Dispositivo> = emptyList(),
    val wifi: WifiGalaxy = WifiGalaxy.SIN_DATO,
    val opcion: OpcionPreset = OpcionPreset.AUTO,
    val tasa: Int = 48000,
    /** Error o aviso del servicio que el usuario ya cerró. */
    val descartado: String? = null,
    /** Leyendo los metadatos de las canciones recién elegidas. */
    val agregando: Boolean = false,
)

data class UiEstado(
    val local: Local = Local(),
    val servicio: EstadoServicio = EstadoServicio(),
    val canciones: List<Cancion> = emptyList(),
) {
    val error: String? get() = servicio.error?.takeIf { it != local.descartado }
    val aviso: String? get() = servicio.aviso?.takeIf { it != local.descartado }
}

/**
 * Fachada de la UI: combina [EstadoEmisor] (lo escribe el servicio) con el estado local, y manda
 * los pedidos a [ServicioEmisor] por intents. Toda la E/S ocurre en el hilo del servicio.
 */
class EmisorViewModel(app: Application) : AndroidViewModel(app) {

    private val adaptador: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val local = MutableStateFlow(Local(btDisponible = adaptador != null))

    init {
        Almacen.iniciar(app)
        ColaMusica.cargar(app)
    }

    val ui: StateFlow<UiEstado> = combine(local, EstadoEmisor.estado, ColaMusica.canciones, ::UiEstado)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            UiEstado(local.value, EstadoEmisor.estado.value, ColaMusica.canciones.value),
        )

    fun tienePermiso(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getApplication<Application>().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // bondedDevices solo se consulta si tienePermiso().
    fun refrescar() {
        val a = adaptador ?: return
        val permiso = tienePermiso()
        val encendido = a.isEnabled
        val vinculados = if (permiso && encendido) {
            a.bondedDevices.orEmpty()
                .map { Dispositivo(it.name ?: it.address, it.address) }
                .sortedBy { it.nombre.lowercase(Locale.getDefault()) }
        } else {
            emptyList()
        }
        local.update { it.copy(permisoOk = permiso, btEncendido = encendido, vinculados = vinculados) }
    }

    fun conectar(d: Dispositivo) {
        if (!tienePermiso() || EstadoEmisor.estado.value.enlace != Enlace.Inactivo) return
        local.update { it.copy(descartado = null) }
        val app = getApplication<Application>()
        app.startForegroundService(
            intent(ServicioEmisor.ACCION_CONECTAR)
                .putExtra(ServicioEmisor.EXTRA_NOMBRE, d.nombre)
                .putExtra(ServicioEmisor.EXTRA_DIRECCION, d.direccion),
        )
    }

    fun desconectar() = enviar(intent(ServicioEmisor.ACCION_DETENER))

    fun probarEnlace() = enviar(intent(ServicioEmisor.ACCION_PROBAR).putExtra(ServicioEmisor.EXTRA_WIFI, local.value.wifi.name))

    fun iniciarTono() = enviar(
        intent(ServicioEmisor.ACCION_TONO)
            .putExtra(ServicioEmisor.EXTRA_PRESET, local.value.opcion.name)
            .putExtra(ServicioEmisor.EXTRA_TASA, local.value.tasa),
    )

    fun detenerAudio() = enviar(intent(ServicioEmisor.ACCION_DETENER_AUDIO))

    // --- Música.

    fun agregarCanciones(uris: List<Uri>) {
        if (uris.isEmpty()) return
        local.update { it.copy(agregando = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ColaMusica.agregar(getApplication(), uris)
            } finally {
                local.update { it.copy(agregando = false) }
            }
            enviar(intent(ServicioEmisor.ACCION_COLA))
        }
    }

    fun vaciarCola() {
        enviar(intent(ServicioEmisor.ACCION_DETENER_AUDIO))
        viewModelScope.launch(Dispatchers.IO) {
            ColaMusica.vaciar(getApplication())
            enviar(intent(ServicioEmisor.ACCION_COLA))
        }
    }

    fun reproducir(indice: Int) = enviar(
        intent(ServicioEmisor.ACCION_REPRODUCIR)
            .putExtra(ServicioEmisor.EXTRA_INDICE, indice)
            .putExtra(ServicioEmisor.EXTRA_PRESET, local.value.opcion.name),
    )

    fun alternarPausa() = enviar(intent(ServicioEmisor.ACCION_PAUSA))

    fun siguiente() = enviar(intent(ServicioEmisor.ACCION_SIGUIENTE))

    fun anterior() = enviar(intent(ServicioEmisor.ACCION_ANTERIOR))

    fun elegirWifi(w: WifiGalaxy) = local.update { it.copy(wifi = w) }

    /** Con música sonando, el preset nuevo se aplica ya (sigue desde el mismo punto). */
    fun elegirPreset(o: OpcionPreset) {
        local.update { it.copy(opcion = o) }
        if (EstadoEmisor.estado.value.reproduccion?.modo == Modo.MUSICA) {
            enviar(intent(ServicioEmisor.ACCION_PRESET).putExtra(ServicioEmisor.EXTRA_PRESET, o.name))
        }
    }

    fun elegirTasa(t: Int) = local.update { it.copy(tasa = t) }

    fun cerrarError() = local.update { it.copy(descartado = ui.value.error ?: ui.value.aviso) }

    fun borrarMediciones() = EstadoEmisor.actualizar { it.copy(mediciones = emptyList()) }

    fun borrarPruebasTono() = EstadoEmisor.actualizar { e ->
        e.copy(pruebasTono = e.pruebasTono.filter { !it.terminado })
    }

    fun borrarMusica() = EstadoEmisor.actualizar { e -> e.copy(musica = e.musica.filter { !it.terminado }) }

    private fun intent(accion: String) = Intent(getApplication(), ServicioEmisor::class.java).setAction(accion)

    /** El servicio ya corre en primer plano: un startService común le llega. */
    private fun enviar(i: Intent) {
        if (EstadoEmisor.estado.value.enlace != Enlace.Inactivo) getApplication<Application>().startService(i)
    }

    private fun cabecera(): String =
        "Emisor: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, " +
            "API ${Build.VERSION.SDK_INT}) · ParlanteY Emisor ${versionApp()} · protocolo v${Protocolo.VERSION}"

    /** Pruebas de enlace en formato de tabla Markdown, para pegar en NOTAS.md. */
    fun textoParaNotas(): String = buildString {
        appendLine(cabecera())
        appendLine()
        appendLine("| Fecha y hora | Receptor | Wi-Fi Galaxy Y | KB/s (receptor) | KB/s (emisor) | RTT mín/prom/máx (ms) | KB en s | Preset Auto |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (m in EstadoEmisor.estado.value.mediciones) {
            appendLine(
                "| ${m.hora} | ${m.receptor} | ${m.wifi.etiqueta} | ${m.kBpsReceptor.f1()} | ${m.kBpsEmisor.f1()} | " +
                    "${m.rttMinMs.f0()}/${m.rttPromMs.f0()}/${m.rttMaxMs.f0()} | " +
                    "${(m.bytes / 1000.0).f1()} en ${(m.duracionMs / 1000.0).f1()} | " +
                    "${m.presetAuto?.nombre ?: "ninguno"}${if (m.bytesCoinciden) "" else " ⚠ bytes"} |",
            )
        }
    }

    /** Pruebas de tono (Fase 2) en formato de tabla Markdown. */
    fun textoNotasFase2(): String = buildString {
        appendLine(cabecera())
        appendLine()
        appendLine(
            "| Inicio | Duración | Preset | Formato | Cortes | Buffer mín/prom/máx (ms) | Reconexiones | " +
                "Encender BT → suena (s) |",
        )
        appendLine("|---|---|---|---|---|---|---|---|")
        for (r in EstadoEmisor.estado.value.pruebasTono) {
            val dur = (r.hastaMs - r.desdeMs) / 1000
            val buffer = if (r.muestrasBuffer == 0L) "—" else "${r.bufferMinMs}/${r.bufferPromMs}/${r.bufferMaxMs}"
            val preset = r.preset.nombre + if (r.opcion == OpcionPreset.AUTO) " (Auto)" else ""
            appendLine(
                "| ${r.inicio} | ${"%d:%02d".format(dur / 60, dur % 60)}${if (r.terminado) "" else " (en curso)"} | " +
                    "$preset | ${tasaTexto(r.tasaSalida)} kHz ${if (r.canales == 2) "estéreo" else "mono"} | ${r.cortes} | " +
                    "$buffer | ${r.reconexiones} | ${r.recuperacionMs?.let { (it / 1000.0).f1() } ?: "—"} |",
            )
        }
    }

    /** Sesiones de música (Fase 3) en formato de tabla Markdown. */
    fun textoNotasFase3(): String = buildString {
        appendLine(cabecera())
        appendLine()
        appendLine(
            "| Inicio | Duración | Pistas | Tipos | Preset | Formatos | Cortes | Buffer mín/prom/máx (ms) | " +
                "Reconexiones | Pausa → STATE (ms) | Siguiente → STATE (ms) | Completa |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in EstadoEmisor.estado.value.musica) {
            val dur = (r.hastaMs - r.desdeMs) / 1000
            val buffer = if (r.muestrasBuffer == 0L) "—" else "${r.bufferMinMs}/${r.bufferPromMs}/${r.bufferMaxMs}"
            val presets = r.presets.joinToString(", ").ifEmpty { "—" } + if (r.opcion == OpcionPreset.AUTO) " (Auto)" else ""
            val completa = when {
                !r.terminado -> "en curso"
                r.completa -> "sí"
                else -> "no (detenida)"
            }
            appendLine(
                "| ${r.inicio} | ${"%d:%02d".format(dur / 60, dur % 60)} | ${r.pistas.size}" +
                    "${if (r.errores > 0) " (${r.errores} sin abrir)" else ""} | ${r.tipos.joinToString(", ").ifEmpty { "—" }} | " +
                    "$presets | ${r.formatos.joinToString(", ").ifEmpty { "—" }} | ${r.cortes} | $buffer | ${r.reconexiones} | " +
                    "${latencias(r.pausasMs)} | ${latencias(r.siguientesMs)} | $completa |",
            )
        }
    }

    private fun latencias(ms: List<Long>): String =
        if (ms.isEmpty()) "—" else "${ms.joinToString(", ")} (máx ${ms.max()})"

    private fun versionApp(): String {
        val app = getApplication<Application>()
        return try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            "?"
        }
    }
}

/** 48000 → "48"; 44100 → "44,1". */
fun tasaTexto(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else (hz / 1000.0).f1()

fun Double.f0(): String = String.format(Locale.getDefault(), "%.0f", this)

fun Double.f1(): String = String.format(Locale.getDefault(), "%.1f", this)
