package cl.parlantey.emisor

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cl.parlantey.core.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda en SharedPreferences las mediciones de enlace (y los KB/s de cada receptor, base del
 * preset Auto) y los resúmenes de las pruebas de tono y de música: las tablas para NOTAS no se
 * pierden si Android cierra la app. Escribe como mucho cada 2 s.
 */
object Almacen {
    private const val PREFS = "almacen"
    private const val ESPERA_ENTRE_ESCRITURAS_MS = 2000L

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var iniciado = false

    /** Carga lo guardado en [EstadoEmisor] y empieza a guardar los cambios. Idempotente. */
    fun iniciar(contexto: Context) {
        if (iniciado) return
        synchronized(this) {
            if (iniciado) return
            val app = contexto.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            try {
                val mediciones = lista(prefs.getString("mediciones", null), ::medicion)
                val tono = lista(prefs.getString("pruebasTono", null), ::resumenTono)
                val musica = lista(prefs.getString("musica", null), ::resumenMusica)
                val kBps = try {
                    prefs.getString("kBps", null)?.let { t ->
                        val o = JSONObject(t)
                        o.keys().asSequence().associateWith { o.getDouble(it) }
                    } ?: emptyMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                EstadoEmisor.actualizar {
                    it.copy(
                        mediciones = mediciones + it.mediciones,
                        // Lo que quedó "en curso" cuando se cerró la app ya no sigue.
                        pruebasTono = tono.map { r -> r.copy(terminado = true) } + it.pruebasTono,
                        musica = musica.map { r -> r.copy(terminado = true) } + it.musica,
                        kBpsPorReceptor = kBps + it.kBpsPorReceptor,
                    )
                }
            } catch (e: Exception) {
                Log.w(Supervisor.TAG, "datos guardados ilegibles", e)
            }
            alcance.launch {
                EstadoEmisor.estado
                    .map { listOf(it.mediciones, it.pruebasTono, it.musica, it.kBpsPorReceptor) }
                    .distinctUntilChanged()
                    .conflate()
                    .collect {
                        guardar(app)
                        delay(ESPERA_ENTRE_ESCRITURAS_MS)
                    }
            }
            iniciado = true
        }
    }

    private fun guardar(contexto: Context) {
        val e = EstadoEmisor.estado.value
        val kBps = JSONObject()
        for ((k, v) in e.kBpsPorReceptor) if (!v.isNaN()) kBps.put(k, v)
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("mediciones", JSONArray(e.mediciones.map(::json)).toString())
            putString("pruebasTono", JSONArray(e.pruebasTono.map(::json)).toString())
            putString("musica", JSONArray(e.musica.map(::json)).toString())
            putString("kBps", kBps.toString())
        }
    }

    /** Un registro ilegible (p. ej. de otra versión) se salta; no se pierde el resto. */
    private fun <T> lista(texto: String?, leer: (JSONObject) -> T): List<T> {
        if (texto == null) return emptyList()
        val a = try {
            JSONArray(texto)
        } catch (e: Exception) {
            Log.w(Supervisor.TAG, "lista guardada ilegible", e)
            return emptyList()
        }
        return (0 until a.length()).mapNotNull {
            try {
                leer(a.getJSONObject(it))
            } catch (e: Exception) {
                Log.w(Supervisor.TAG, "registro guardado ilegible", e)
                null
            }
        }
    }

    private fun JSONObject.longs(clave: String): List<Long> =
        optJSONArray(clave)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()

    private fun JSONObject.ints(clave: String): List<Int> =
        optJSONArray(clave)?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()

    private fun JSONObject.textos(clave: String): List<String> =
        optJSONArray(clave)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    private fun JSONObject.opcion(): OpcionPreset =
        OpcionPreset.entries.firstOrNull { it.name == optString("opcion") } ?: OpcionPreset.AUTO

    private fun json(m: Medicion) = JSONObject()
        .put("hora", m.hora).put("receptor", m.receptor).put("wifi", m.wifi.name)
        .put("kBpsReceptor", m.kBpsReceptor).put("kBpsEmisor", m.kBpsEmisor)
        .put("rttMinMs", m.rttMinMs).put("rttPromMs", m.rttPromMs).put("rttMaxMs", m.rttMaxMs)
        .put("bytes", m.bytes).put("duracionMs", m.duracionMs).put("bytesCoinciden", m.bytesCoinciden)

    private fun medicion(o: JSONObject) = Medicion(
        hora = o.getString("hora"),
        receptor = o.getString("receptor"),
        wifi = WifiGalaxy.entries.firstOrNull { it.name == o.optString("wifi") } ?: WifiGalaxy.SIN_DATO,
        kBpsReceptor = o.getDouble("kBpsReceptor"),
        kBpsEmisor = o.getDouble("kBpsEmisor"),
        rttMinMs = o.getDouble("rttMinMs"),
        rttPromMs = o.getDouble("rttPromMs"),
        rttMaxMs = o.getDouble("rttMaxMs"),
        bytes = o.getLong("bytes"),
        duracionMs = o.getLong("duracionMs"),
        bytesCoinciden = o.getBoolean("bytesCoinciden"),
    )

    private fun json(r: ResumenTono) = JSONObject()
        .put("inicio", r.inicio).put("duracionMs", r.hastaMs - r.desdeMs).put("preset", r.preset.name)
        .put("opcion", r.opcion.name).put("tasaSalida", r.tasaSalida).put("canales", r.canales)
        .put("cortes", r.cortes).put("bufferMinMs", r.bufferMinMs).put("bufferMaxMs", r.bufferMaxMs)
        .put("bufferSumaMs", r.bufferSumaMs).put("muestrasBuffer", r.muestrasBuffer)
        .put("reconexiones", r.reconexiones).put("recuperacionMs", r.recuperacionMs ?: -1)

    private fun resumenTono(o: JSONObject) = ResumenTono(
        inicio = o.getString("inicio"),
        desdeMs = 0,
        hastaMs = o.getLong("duracionMs"),
        preset = Preset.valueOf(o.getString("preset")),
        opcion = o.opcion(),
        tasaSalida = o.getInt("tasaSalida"),
        canales = o.getInt("canales"),
        cortes = o.getLong("cortes"),
        bufferMinMs = o.getLong("bufferMinMs"),
        bufferMaxMs = o.getLong("bufferMaxMs"),
        bufferSumaMs = o.getLong("bufferSumaMs"),
        muestrasBuffer = o.getLong("muestrasBuffer"),
        reconexiones = o.getInt("reconexiones"),
        recuperacionMs = o.getLong("recuperacionMs").takeIf { it >= 0 },
        terminado = true,
    )

    private fun json(r: ResumenMusica) = JSONObject()
        .put("inicio", r.inicio).put("duracionMs", r.hastaMs - r.desdeMs).put("opcion", r.opcion.name)
        .put("presets", JSONArray(r.presets)).put("pistas", JSONArray(r.pistas)).put("tipos", JSONArray(r.tipos))
        .put("formatos", JSONArray(r.formatos)).put("cortes", r.cortes).put("bufferMinMs", r.bufferMinMs)
        .put("bufferMaxMs", r.bufferMaxMs).put("bufferSumaMs", r.bufferSumaMs).put("muestrasBuffer", r.muestrasBuffer)
        .put("reconexiones", r.reconexiones).put("pausasMs", JSONArray(r.pausasMs))
        .put("siguientesMs", JSONArray(r.siguientesMs)).put("errores", r.errores).put("completa", r.completa)

    private fun resumenMusica(o: JSONObject) = ResumenMusica(
        inicio = o.getString("inicio"),
        desdeMs = 0,
        hastaMs = o.getLong("duracionMs"),
        opcion = o.opcion(),
        presets = o.textos("presets"),
        pistas = o.ints("pistas"),
        tipos = o.textos("tipos"),
        formatos = o.textos("formatos"),
        cortes = o.getLong("cortes"),
        bufferMinMs = o.getLong("bufferMinMs"),
        bufferMaxMs = o.getLong("bufferMaxMs"),
        bufferSumaMs = o.getLong("bufferSumaMs"),
        muestrasBuffer = o.getLong("muestrasBuffer"),
        reconexiones = o.getInt("reconexiones"),
        pausasMs = o.longs("pausasMs"),
        siguientesMs = o.longs("siguientesMs"),
        errores = o.optInt("errores"),
        completa = o.optBoolean("completa"),
        terminado = true,
    )
}
