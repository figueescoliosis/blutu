package cl.parlantey.emisor

import cl.parlantey.core.Hello
import cl.parlantey.core.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Dispositivo(val nombre: String, val direccion: String)

enum class WifiGalaxy(val etiqueta: String) {
    SIN_DATO("?"),
    ENCENDIDO("ON"),
    APAGADO("OFF"),
}

/** Preset elegido en la UI: Auto o uno fijo. */
enum class OpcionPreset(val etiqueta: String, val fijo: Preset?) {
    AUTO("Auto", null),
    ALTA("Alta", Preset.ALTA),
    NORMAL("Normal", Preset.NORMAL),
    SEGURA("Segura", Preset.SEGURA),
    ;

    /** El preset que se usa. Auto: el más alto con lo medido; sin medición, Normal; si nada alcanza, Segura. */
    fun resolver(kBpsMedidos: Double): Preset = fijo ?: Preset.elegirAuto(kBpsMedidos) ?: Preset.SEGURA
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

/** Estado de la conexión, según el supervisor del servicio. */
sealed interface Enlace {
    data object Inactivo : Enlace

    data class Conectando(val dispositivo: Dispositivo, val intento: Int) : Enlace

    data class Conectado(
        val dispositivo: Dispositivo,
        val receptor: InfoReceptor,
        /** [android.os.SystemClock.elapsedRealtime] al recibir el HELLO. */
        val desdeMs: Long,
        val rttMs: Double? = null,
        val midiendo: Boolean = false,
    ) : Enlace

    data class Reconectando(
        val dispositivo: Dispositivo,
        val intento: Int,
        /** elapsedRealtime del próximo intento. */
        val proximoMs: Long,
        val causa: String,
    ) : Enlace

    /** El Bluetooth de este teléfono está apagado: se reconecta solo al encenderlo. */
    data class BluetoothApagado(val dispositivo: Dispositivo) : Enlace
}

/** Último STATE del receptor y el nivel estimado por el control de flujo. */
data class EnVivo(
    val bufferMs: Long,
    val cortes: Long,
    val reproduciendo: Boolean,
    val nivelMs: Long,
    val ultimoSeq: Long,
)

/** Tono de prueba en curso (sigue vivo durante las reconexiones). */
data class Tono(
    val opcion: OpcionPreset,
    val preset: Preset,
    val tasaOrigen: Int,
    val tasaSalida: Int,
    val canales: Int,
    val vivo: EnVivo? = null,
)

/** Resumen de una prueba de tono, para «Copiar para NOTAS (Fase 2)». */
data class ResumenTono(
    val inicio: String,
    val desdeMs: Long,
    val hastaMs: Long,
    val preset: Preset,
    val opcion: OpcionPreset,
    val tasaSalida: Int,
    val canales: Int,
    val cortes: Long = 0,
    val bufferMinMs: Long = Long.MAX_VALUE,
    val bufferMaxMs: Long = 0,
    val bufferSumaMs: Long = 0,
    val muestrasBuffer: Long = 0,
    val reconexiones: Int = 0,
    /** Desde STATE_ON del Bluetooth hasta que el receptor vuelve a sonar, en ms (la última vez). */
    val recuperacionMs: Long? = null,
    val terminado: Boolean = false,
) {
    val bufferPromMs: Long get() = if (muestrasBuffer == 0L) 0 else bufferSumaMs / muestrasBuffer
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
    val presetAuto: Preset? get() = Preset.elegirAuto(kBpsReceptor)
}

data class EstadoServicio(
    val enlace: Enlace = Enlace.Inactivo,
    val tono: Tono? = null,
    val pruebasTono: List<ResumenTono> = emptyList(),
    val mediciones: List<Medicion> = emptyList(),
    val error: String? = null,
    /** El cierre no fue un error (p. ej. "El receptor se detuvo"). */
    val aviso: String? = null,
) {
    /** KB/s de la última prueba de enlace (NaN si no hay): base del preset Auto. */
    val ultimoKBps: Double get() = mediciones.lastOrNull()?.kBpsReceptor ?: Double.NaN
}

/** Estado del emisor. Solo lo escribe [ServicioEmisor] (y su supervisor); la UI lo lee. */
object EstadoEmisor {
    private val _estado = MutableStateFlow(EstadoServicio())
    val estado: StateFlow<EstadoServicio> = _estado.asStateFlow()

    fun actualizar(cambio: (EstadoServicio) -> EstadoServicio) = _estado.update(cambio)
}
