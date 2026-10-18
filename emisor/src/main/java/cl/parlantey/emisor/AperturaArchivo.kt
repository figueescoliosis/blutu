package cl.parlantey.emisor

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import cl.parlantey.core.ControlCola
import cl.parlantey.core.Fuente
import cl.parlantey.core.Track
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * Un archivo de audio decodificado con MediaExtractor + MediaCodec.
 *
 * La apertura corre en [ejecutor] (no bloquea al hilo de E/S): abre el archivo, elige la primera
 * pista de audio, arranca el decodificador, hace el seek y decodifica hasta el primer PCM. Recién
 * ahí se conocen la tasa y los canales de verdad: los del formato de salida del decodificador, no
 * los del contenedor (HE-AAC entrega el doble de la tasa que declara el archivo).
 *
 * Ya abierta, es la [Fuente] de su PCM16: [leer] alimenta y vacía el decodificador sin esperar
 * (timeout 0) en el hilo de E/S. Acepta PCM de 8, 16, 24 y 32 bits y float. Si el decodificador se
 * traba o falla, la pista termina antes (y se anota en el log).
 */
class AperturaArchivo(
    private val contexto: Context,
    private val uri: Uri,
    private val desdeMs: Long,
    private val track: Track,
    ejecutor: Executor,
) : ControlCola.Apertura, Fuente {

    private val cerrojo = Any()
    private var abriendo = true // con cerrojo
    @Volatile private var cerrada = false
    @Volatile private var lista = false
    @Volatile private var error: String? = null
    /** Corta una apertura lenta (p. ej. un archivo en la nube que se está bajando) al cerrar. */
    private val cancelar = CancellationSignal()

    private var afd: AssetFileDescriptor? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null

    private var tasa = 0
    private var canales = 0
    private var codificacion = AudioFormat.ENCODING_PCM_16BIT
    /** Ya se entregó PCM: el formato de salida quedó fijo. */
    private var conDatos = false
    private val info = MediaCodec.BufferInfo()
    private var finEntrada = false
    private var finSalida = false
    private val desdeUs = desdeMs * 1000
    private var sinProgresoDesdeNs = 0L

    /** PCM16 intercalado ya decodificado y no entregado: muestras [pcmInicio, pcmFin). */
    private var pcm = ShortArray(16384)
    private var pcmInicio = 0
    private var pcmFin = 0

    init {
        try {
            ejecutor.execute(::abrir)
        } catch (e: RejectedExecutionException) {
            synchronized(cerrojo) { abriendo = false }
            error = "se está cerrando"
            lista = true
        }
    }

    // --- Apertura.

    override fun lista(): Boolean = lista

    override fun fuente(): Fuente? = if (lista && error == null) this else null

    override fun track(): Track = track

    override fun error(): String? = error

    override fun cerrar() {
        synchronized(cerrojo) {
            if (cerrada) return
            cerrada = true
            cancelar.cancel()
            if (!abriendo) liberar() // si no, libera la tarea de apertura al terminar
        }
    }

    private fun abrir() {
        var falla: String? = "no se pudo abrir"
        try {
            if (!cerrada) abrirDecodificador()
            falla = null
        } catch (e: Throwable) {
            if (!cerrada) Log.w(Supervisor.TAG, "no se pudo abrir $uri", e)
            falla = when (e) {
                is SecurityException -> "sin permiso para leerlo (vuelve a agregarlo)"
                is IOException -> e.message ?: "no se pudo leer"
                is OperationCanceledException -> "cancelada"
                else -> "el teléfono no lo puede decodificar (${e.javaClass.simpleName})"
            }
        } finally {
            // Siempre: si no, ControlCola esperaría esta apertura para siempre.
            synchronized(cerrojo) {
                abriendo = false
                if (falla != null) {
                    error = falla
                    liberar()
                } else if (cerrada) {
                    liberar()
                }
                lista = true
            }
        }
    }

    private fun abrirDecodificador() {
        val d = contexto.contentResolver.openAssetFileDescriptor(uri, "r", cancelar)
            ?: throw IOException("no se pudo abrir")
        afd = d
        if (cerrada) return
        val ex = MediaExtractor()
        extractor = ex
        ex.setDataSource(d)
        val pista = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("no tiene audio")
        ex.selectTrack(pista)
        if (cerrada) return
        val formato = ex.getTrackFormat(pista)
        val mime = formato.getString(MediaFormat.KEY_MIME)!!
        val c = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: IOException) {
            throw IOException("el teléfono no decodifica $mime")
        } catch (e: IllegalArgumentException) {
            throw IOException("el teléfono no decodifica $mime")
        }
        codec = c
        c.configure(formato, null, null, 0)
        c.start()
        if (desdeUs > 0) ex.seekTo(desdeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // Hasta el primer PCM: ahí el formato de salida es el definitivo.
        val limite = System.nanoTime() + PLAZO_APERTURA_NS
        while (pcmFin == 0 && !finSalida) {
            if (cerrada) return
            if (System.nanoTime() - limite > 0) throw IOException("el decodificador no entregó audio")
            avanzar(ESPERA_APERTURA_US)
        }
        if (tasa <= 0 || canales <= 0) {
            // Sin ningún PCM: archivo vacío o el seek pasó el final.
            tasa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            canales = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }

    private fun liberar() {
        // Sin stop(): release() basta y no frena al hilo de E/S en el cambio de pista.
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
        try {
            afd?.close()
        } catch (_: IOException) {
        }
        codec = null
        extractor = null
        afd = null
    }

    // --- Fuente (hilo de E/S).

    override fun tasa(): Int = tasa

    override fun canales(): Int = canales

    override fun leer(destino: ShortArray, desdeFrame: Int, frames: Int): Int {
        if (pcmFin == pcmInicio) {
            if (finSalida || codec == null) return Fuente.FIN
            val progreso = try {
                avanzar(0)
            } catch (e: Exception) {
                Log.w(Supervisor.TAG, "falla al decodificar $uri: la pista termina aquí", e)
                finSalida = true
                false
            }
            val ahora = System.nanoTime()
            if (progreso) {
                sinProgresoDesdeNs = 0
            } else if (sinProgresoDesdeNs == 0L) {
                sinProgresoDesdeNs = ahora
            } else if (ahora - sinProgresoDesdeNs > PLAZO_TRABADO_NS) {
                Log.w(Supervisor.TAG, "el decodificador no avanza: la pista termina aquí")
                finSalida = true
            }
            if (pcmFin == pcmInicio) return if (finSalida) Fuente.FIN else 0
        }
        val n = minOf(frames, (pcmFin - pcmInicio) / canales)
        System.arraycopy(pcm, pcmInicio, destino, desdeFrame * canales, n * canales)
        pcmInicio += n * canales
        return n
    }

    /** Entrega al decodificador las entradas que acepte y saca a lo más una salida. true si avanzó. */
    private fun avanzar(esperaUs: Long): Boolean {
        val c = codec ?: return false
        val ex = extractor ?: return false
        var progreso = false
        while (!finEntrada) {
            val i = c.dequeueInputBuffer(0)
            if (i < 0) break
            val buf = c.getInputBuffer(i) ?: break
            val n = ex.readSampleData(buf, 0)
            if (n < 0) {
                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                finEntrada = true
            } else {
                c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                ex.advance()
            }
            progreso = true
        }
        val o = c.dequeueOutputBuffer(info, esperaUs)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            formatoDeSalida(c.outputFormat)
            return true
        }
        if (o < 0) return progreso
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finSalida = true
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && !finSalidaPorFormato) {
            if (!conDatos) formatoDeSalida(c.getOutputFormat(o))
            val buf = c.getOutputBuffer(o)
            if (buf != null && !finSalidaPorFormato) {
                val antes = pcmFin
                convertir(buf, info)
                // Lo que el seek descarta no fija el formato: puede cambiar todavía.
                if (pcmFin != antes) conDatos = true
            }
        }
        c.releaseOutputBuffer(o, false)
        return true
    }

    private var finSalidaPorFormato = false

    private fun formatoDeSalida(f: MediaFormat) {
        val t = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val cod = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (conDatos && (t != tasa || ch != canales || cod != codificacion)) {
            // A media pista no se puede cambiar de formato: termina aquí.
            Log.w(Supervisor.TAG, "el formato cambió a media pista ($tasa → $t Hz, $canales → $ch canales): termina aquí")
            finSalidaPorFormato = true
            finSalida = true
            return
        }
        tasa = t
        canales = ch
        codificacion = cod
    }

    /** Agrega el PCM de [buf] a [pcm] como int16, sin lo anterior a [desdeUs]. */
    private fun convertir(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (canales <= 0) return
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val datos = buf.slice().order(ByteOrder.nativeOrder())
        val bytesMuestra = when (codificacion) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val frames = info.size / (bytesMuestra * canales)
        var saltar = 0
        if (info.presentationTimeUs < desdeUs && tasa > 0) {
            saltar = ((desdeUs - info.presentationTimeUs) * tasa / 1_000_000L).coerceAtMost(frames.toLong()).toInt()
        }
        val n = (frames - saltar) * canales
        if (n <= 0) return
        reservar(n)
        val desde = saltar * canales
        var o = pcmFin
        when (codificacion) {
            AudioFormat.ENCODING_PCM_8BIT -> for (i in desde until desde + n) {
                pcm[o++] = (((datos.get(i).toInt() and 0xFF) - 128) shl 8).toShort()
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val f = datos.asFloatBuffer()
                for (i in desde until desde + n) {
                    val v = f.get(i)
                    pcm[o++] = if (v.isNaN()) 0 else (v * 32767f).coerceIn(-32768f, 32767f).roundToInt().toShort()
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val e = datos.asIntBuffer()
                for (i in desde until desde + n) pcm[o++] = (e.get(i) shr 16).toShort()
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val alto = if (datos.order() == ByteOrder.LITTLE_ENDIAN) 2 else 0
                val medio = 1
                for (i in desde until desde + n) {
                    val b = i * 3
                    pcm[o++] = ((datos.get(b + alto).toInt() shl 8) or (datos.get(b + medio).toInt() and 0xFF)).toShort()
                }
            }
            else -> {
                val s = datos.asShortBuffer()
                s.position(desde)
                s.get(pcm, o, n)
                o += n
            }
        }
        pcmFin = o
    }

    /** Lugar para [n] muestras más al final de [pcm] (corre lo pendiente al inicio, o crece). */
    private fun reservar(n: Int) {
        if (pcmInicio > 0) {
            System.arraycopy(pcm, pcmInicio, pcm, 0, pcmFin - pcmInicio)
            pcmFin -= pcmInicio
            pcmInicio = 0
        }
        if (pcmFin + n > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, pcmFin + n))
    }

    companion object {
        private const val PLAZO_APERTURA_NS = 10_000_000_000L
        private const val ESPERA_APERTURA_US = 10_000L
        private const val PLAZO_TRABADO_NS = 5_000_000_000L
    }
}
