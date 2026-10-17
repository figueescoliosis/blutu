package cl.parlantey.emisor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.parlantey.core.ByeRecibidoException
import cl.parlantey.core.Hello
import cl.parlantey.core.Protocolo
import cl.parlantey.core.ResultadoPrueba
import cl.parlantey.core.VersionIncompatibleException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class Dispositivo(val nombre: String, val direccion: String)

enum class WifiGalaxy(val etiqueta: String) {
    SIN_DATO("?"),
    ENCENDIDO("ON"),
    APAGADO("OFF"),
}

data class InfoReceptor(
    val nombre: String,
    val version: Int,
    val apiLevel: Int,
    val maxCanales: Int,
    val capacidadBufferMs: Long,
) {
    constructor(h: Hello) : this(h.nombre, h.version, h.apiLevel, h.maxCanales, h.capacidadBufferMs)
}

sealed interface EstadoConexion {
    data object Desconectado : EstadoConexion

    data class Conectando(val dispositivo: Dispositivo) : EstadoConexion

    data class Conectado(
        val dispositivo: Dispositivo,
        val receptor: InfoReceptor,
        /** [SystemClock.elapsedRealtime] al recibir el HELLO. */
        val desdeMs: Long,
        /** [SystemClock.elapsedRealtime] de la última actualización. */
        val ahoraMs: Long,
        val rttMs: Double? = null,
        val pings: Int = 0,
        val midiendo: Boolean = false,
    ) : EstadoConexion
}

data class Medicion(
    val hora: String,
    val receptor: String,
    val wifi: WifiGalaxy,
    val kBpsReceptor: Double,
    val kBpsEmisor: Double,
    val rttMinMs: Double,
    val rttPromMs: Double,
    val rttMaxMs: Double,
    val bytes: Long,
    val duracionMs: Long,
    val bytesCoinciden: Boolean,
) {
    val presetAuto: Preset? get() = Preset.auto(kBpsReceptor)
}

data class UiEstado(
    val btDisponible: Boolean = true,
    val permisoOk: Boolean = false,
    val btEncendido: Boolean = false,
    val vinculados: List<Dispositivo> = emptyList(),
    val conexion: EstadoConexion = EstadoConexion.Desconectado,
    val wifi: WifiGalaxy = WifiGalaxy.SIN_DATO,
    val mediciones: List<Medicion> = emptyList(),
    val error: String? = null,
)

/**
 * Fase 0: conexión con el receptor y prueba de enlace. Una sola corrutina ("sesión") hace toda
 * la E/S del socket; la UI le manda pedidos por [comandos].
 */
class EmisorViewModel(app: Application) : AndroidViewModel(app) {

    private enum class Comando { PROBAR }

    private val adaptador: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _ui = MutableStateFlow(UiEstado(btDisponible = adaptador != null))
    val ui: StateFlow<UiEstado> = _ui.asStateFlow()

    private val comandos = Channel<Comando>(Channel.CONFLATED)
    private var sesion: Job? = null
    @Volatile private var conexionActual: ConexionReceptor? = null

    /**
     * Identifica la sesión vigente. conectar() y desconectar() lo cambian (en el hilo principal)
     * antes de tocar el estado; una sesión que ya no es la vigente no puede publicar nada.
     */
    @Volatile private var idSesion = 0

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
        _ui.update { it.copy(permisoOk = permiso, btEncendido = encendido, vinculados = vinculados) }
    }

    fun conectar(d: Dispositivo) {
        val a = adaptador ?: return
        if (!tienePermiso() || _ui.value.conexion != EstadoConexion.Desconectado) return
        val anterior = sesion
        val conexion = ConexionReceptor(a.getRemoteDevice(d.direccion))
        conexionActual = conexion
        while (comandos.tryReceive().isSuccess) {
            // Descarta pedidos de una sesión anterior.
        }
        val id = ++idSesion
        _ui.update { it.copy(conexion = EstadoConexion.Conectando(d), error = null) }
        sesion = viewModelScope.launch(Dispatchers.IO) {
            anterior?.join() // la sesión anterior puede estar despidiéndose
            ejecutarSesion(id, d, conexion)
        }
    }

    fun probarEnlace() {
        val c = _ui.value.conexion
        if (c is EstadoConexion.Conectado && !c.midiendo) comandos.trySend(Comando.PROBAR)
    }

    fun desconectar() {
        val estado = _ui.value.conexion
        idSesion++
        sesion?.cancel()
        // connect() y la espera del HELLO no ven la cancelación: se cortan cerrando el socket.
        if (estado is EstadoConexion.Conectando) conexionActual?.cerrar()
        _ui.update { it.copy(conexion = EstadoConexion.Desconectado) }
    }

    fun elegirWifi(w: WifiGalaxy) = _ui.update { it.copy(wifi = w) }

    fun borrarMediciones() = _ui.update { it.copy(mediciones = emptyList()) }

    fun cerrarError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        // viewModelScope ya se canceló; si estaba conectando, hay que cortar el connect().
        if (_ui.value.conexion is EstadoConexion.Conectando) conexionActual?.cerrar()
    }

    private suspend fun ejecutarSesion(id: Int, d: Dispositivo, c: ConexionReceptor) {
        /** Cambia el estado solo si esta sigue siendo la sesión vigente. */
        fun publicar(cambio: (UiEstado) -> UiEstado) = _ui.update { if (idSesion == id) cambio(it) else it }

        fun terminar(mensaje: String) =
            publicar { it.copy(conexion = EstadoConexion.Desconectado, error = mensaje) }

        var etapa = "conectar"
        try {
            vigilado(c, TIEMPO_CONEXION_MS) { c.conectar() }
            etapa = "hello"
            val hello = vigilado(c, TIEMPO_HELLO_MS) { c.esperarHello() }
            etapa = "sesion"
            val desde = SystemClock.elapsedRealtime()
            var estado = EstadoConexion.Conectado(d, InfoReceptor(hello), desde, desde)
            publicar { it.copy(conexion = estado) }

            while (true) {
                when (withTimeoutOrNull(INTERVALO_PING_MS) { comandos.receive() }) {
                    null -> {
                        val rtt = vigilado(c, TIEMPO_PING_MS) { c.ping() }
                        estado = estado.copy(
                            rttMs = rtt / 1e6,
                            pings = estado.pings + 1,
                            ahoraMs = SystemClock.elapsedRealtime(),
                        )
                        publicar { it.copy(conexion = estado) }
                    }
                    Comando.PROBAR -> {
                        estado = estado.copy(midiendo = true)
                        publicar { it.copy(conexion = estado) }
                        val r = vigilado(c, TIEMPO_PRUEBA_MS) { c.probarEnlace() }
                        estado = estado.copy(
                            midiendo = false,
                            rttMs = r.rttPromMs(),
                            ahoraMs = SystemClock.elapsedRealtime(),
                        )
                        val m = medicion(r, hello.nombre, _ui.value.wifi)
                        // La medición se guarda aunque la sesión ya no sea la vigente.
                        _ui.update { it.copy(mediciones = it.mediciones + m) }
                        publicar { it.copy(conexion = estado) }
                    }
                }
            }
        } catch (e: CancellationException) {
            c.despedir() // desconexión pedida por el usuario o cierre de la app
            throw e
        } catch (e: VersionIncompatibleException) {
            terminar(
                "El receptor usa el protocolo v${e.versionRemota} y este emisor el v${Protocolo.VERSION}. " +
                    "Instala la misma versión de ParlanteY en los dos teléfonos.",
            )
        } catch (e: ByeRecibidoException) {
            terminar("El receptor cerró la sesión (código ${e.codigo}).")
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) return // cancelada mientras conectaba
            terminar(
                when {
                    etapa == "conectar" ->
                        "No se pudo conectar con ${d.nombre}. ¿Está abierto ParlanteY Receptor en el Galaxy Y? " +
                            "(${e.message})"
                    c.vencida && etapa == "hello" -> "Conectado, pero el receptor no envió HELLO a tiempo."
                    c.vencida -> "El receptor dejó de responder."
                    else -> "Se perdió la conexión (${e.message})."
                },
            )
        } finally {
            c.cerrar()
            if (conexionActual === c) conexionActual = null
        }
    }

    /**
     * Ejecuta [bloque] (E/S bloqueante) y, si tarda más de [ms], cierra la conexión para que
     * falle con IOException en vez de colgarse.
     */
    private suspend fun <T> vigilado(c: ConexionReceptor, ms: Long, bloque: () -> T): T = coroutineScope {
        val perro = launch {
            delay(ms)
            c.cerrarPorTiempo()
        }
        try {
            bloque()
        } finally {
            perro.cancel()
        }
    }

    /** Mediciones en formato de tabla Markdown, para pegar en NOTAS.md. */
    fun textoParaNotas(): String = buildString {
        appendLine(
            "Emisor: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, " +
                "API ${Build.VERSION.SDK_INT}) · ParlanteY Emisor ${versionApp()} · protocolo v${Protocolo.VERSION}",
        )
        appendLine()
        appendLine("| Fecha y hora | Receptor | Wi-Fi Galaxy Y | KB/s (receptor) | KB/s (emisor) | RTT mín/prom/máx (ms) | KB en s | Preset Auto |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (m in _ui.value.mediciones) {
            appendLine(
                "| ${m.hora} | ${m.receptor} | ${m.wifi.etiqueta} | ${m.kBpsReceptor.f1()} | ${m.kBpsEmisor.f1()} | " +
                    "${m.rttMinMs.f0()}/${m.rttPromMs.f0()}/${m.rttMaxMs.f0()} | " +
                    "${(m.bytes / 1000.0).f1()} en ${(m.duracionMs / 1000.0).f1()} | " +
                    "${m.presetAuto?.nombre ?: "ninguno"}${if (m.bytesCoinciden) "" else " ⚠ bytes"} |",
            )
        }
    }

    private fun versionApp(): String {
        val app = getApplication<Application>()
        return try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            "?"
        }
    }

    private fun medicion(r: ResultadoPrueba, receptor: String, wifi: WifiGalaxy) = Medicion(
        hora = LocalDateTime.now().format(FORMATO_HORA),
        receptor = receptor,
        wifi = wifi,
        kBpsReceptor = r.kBpsReceptor(),
        kBpsEmisor = r.kBpsEmisor(),
        rttMinMs = r.rttMinMs(),
        rttPromMs = r.rttPromMs(),
        rttMaxMs = r.rttMaxMs(),
        bytes = r.bytesRecibidos,
        duracionMs = r.duracionReceptorMs,
        bytesCoinciden = r.bytesRecibidos == r.bytesEnviados,
    )

    companion object {
        private const val TIEMPO_CONEXION_MS = 30_000L // dos intentos (segura + insecure)
        private const val TIEMPO_HELLO_MS = 10_000L
        private const val INTERVALO_PING_MS = 1_000L
        private const val TIEMPO_PING_MS = 5_000L
        private const val TIEMPO_PRUEBA_MS = 40_000L
        private val FORMATO_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

fun Double.f0(): String = String.format(Locale.getDefault(), "%.0f", this)

fun Double.f1(): String = String.format(Locale.getDefault(), "%.1f", this)
