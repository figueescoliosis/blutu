package cl.parlantey.emisor

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.net.toUri
import android.util.Log
import cl.parlantey.core.ControlCola
import cl.parlantey.core.Fuente
import cl.parlantey.core.FuenteTono
import cl.parlantey.core.Track
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Una canción de la cola: un documento elegido con ACTION_OPEN_DOCUMENT (permiso persistente). */
data class Cancion(
    val uri: String,
    val titulo: String,
    val artista: String,
    val duracionMs: Long,
    /** "MP3", "M4A", "FLAC"…: para la tabla de NOTAS. */
    val tipo: String,
) {
    fun track(): Track = Track(titulo.take(MAX_TEXTO), artista.take(MAX_TEXTO), duracionMs.coerceIn(0, 0xFFFFFFFFL))

    companion object {
        private const val MAX_TEXTO = 120
    }
}

/**
 * Cola de canciones, compartida por la UI (agrega y quita) y el servicio (reproduce). Se guarda
 * en SharedPreferences: sigue ahí al reabrir la app. Mientras suena solo se agregan al final.
 */
object ColaMusica {
    private const val PREFS = "cola"
    private const val CLAVE = "canciones"

    private val _canciones = MutableStateFlow<List<Cancion>>(emptyList())
    val canciones: StateFlow<List<Cancion>> = _canciones.asStateFlow()

    @Volatile private var cargada = false

    fun cargar(contexto: Context) {
        if (cargada) return
        synchronized(this) {
            if (cargada) return
            val texto = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE, null)
            if (texto != null) {
                try {
                    val a = JSONArray(texto)
                    _canciones.value = (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        Cancion(o.getString("uri"), o.getString("titulo"), o.optString("artista"),
                            o.optLong("duracionMs"), o.optString("tipo"))
                    }
                } catch (e: Exception) {
                    Log.w(Supervisor.TAG, "cola guardada ilegible", e)
                }
            }
            cargada = true
        }
    }

    /** Agrega al final (lee los metadatos: llamar fuera del hilo principal). */
    fun agregar(contexto: Context, uris: List<Uri>) {
        cargar(contexto)
        val nuevas = uris.mapNotNull { uri ->
            try {
                contexto.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.w(Supervisor.TAG, "sin permiso persistente para $uri", e)
            }
            leerCancion(contexto, uri)
        }
        if (nuevas.isEmpty()) return
        _canciones.update { it + nuevas }
        guardar(contexto)
    }

    fun vaciar(contexto: Context) {
        val viejas = _canciones.value
        _canciones.value = emptyList()
        guardar(contexto)
        for (c in viejas) {
            try {
                contexto.contentResolver.releasePersistableUriPermission(c.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun guardar(contexto: Context) {
        val a = JSONArray()
        for (c in _canciones.value) {
            a.put(
                JSONObject().put("uri", c.uri).put("titulo", c.titulo).put("artista", c.artista)
                    .put("duracionMs", c.duracionMs).put("tipo", c.tipo),
            )
        }
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(CLAVE, a.toString()) }
    }

    private fun leerCancion(contexto: Context, uri: Uri): Cancion? {
        val nombre = try {
            contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: "Sin nombre"
        var titulo: String? = null
        var artista: String? = null
        var duracion = 0L
        var mime: String? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(contexto, uri)
            titulo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artista = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            duracion = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (e: Exception) {
            Log.w(Supervisor.TAG, "sin metadatos para $uri", e)
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        val sinExtension = nombre.substringBeforeLast('.', nombre)
        val extension = nombre.substringAfterLast('.', "").uppercase()
        val tipo = extension.ifEmpty { mime?.substringAfter('/')?.uppercase() ?: "?" }
        return Cancion(uri.toString(), titulo?.takeIf { it.isNotBlank() } ?: sinExtension,
            artista?.takeIf { it.isNotBlank() } ?: "", duracion, tipo)
    }
}

/** Las canciones de la cola (una foto: al agregar, el supervisor recibe una nueva). */
class FabricaArchivos(
    private val contexto: Context,
    val canciones: List<Cancion>,
    private val ejecutor: Executor,
) : ControlCola.Fabrica {
    override fun cantidad(): Int = canciones.size

    override fun abrir(indice: Int, desdeMs: Long): ControlCola.Apertura {
        val c = canciones[indice]
        return AperturaArchivo(contexto, c.uri.toUri(), desdeMs, c.track(), ejecutor)
    }
}

/** El tono de prueba como cola de una pista infinita. */
class FabricaTono(private val tasa: Int) : ControlCola.Fabrica {
    override fun cantidad(): Int = 1

    override fun abrir(indice: Int, desdeMs: Long): ControlCola.Apertura = object : ControlCola.Apertura {
        private val fuente = FuenteTono(tasa, 2)

        override fun lista(): Boolean = true

        override fun fuente(): Fuente = fuente

        override fun track(): Track = TRACK

        override fun error(): String? = null

        override fun cerrar() = Unit
    }

    companion object {
        val TRACK = Track("Tono de prueba", "440 Hz", 0)
    }
}
