package cl.parlantey.emisor

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import cl.parlantey.core.ColaCaptura
import cl.parlantey.core.ControlCola
import cl.parlantey.core.Fuente
import cl.parlantey.core.Track

/**
 * Captura de lo que suena en el teléfono (Android 10+): `AudioRecord` con
 * `AudioPlaybackCaptureConfiguration` sobre una MediaProjection ya concedida, a 48 kHz estéreo.
 * Un hilo con prioridad de audio lee de a 10 ms y escribe en [cola]; el hilo de E/S la envía.
 * Las apps que no permiten la captura (o usos como las llamadas) llegan como silencio.
 */
class Captura @RequiresApi(29) constructor(proyeccion: MediaProjection) {

    val cola = ColaCaptura(TASA, CANALES, MAX_PENDIENTE_MS)

    private val grabador: AudioRecord
    private var hilo: Thread? = null
    @Volatile private var activa = false
    /** Por qué dejó de capturar sola (null si no). */
    @Volatile var error: String? = null
        private set

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(proyeccion)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val formato = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(TASA)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        grabador = construir(config, formato, maxOf(min, TASA * CANALES * 2 / 10)) // ≥ 100 ms
    }

    // El servicio solo la crea con RECORD_AUDIO concedido.
    @SuppressLint("MissingPermission")
    @RequiresApi(29)
    private fun construir(config: AudioPlaybackCaptureConfiguration, formato: AudioFormat, bytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioFormat(formato)
            .setBufferSizeInBytes(bytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

    /** Empieza a capturar. false si el sistema no lo permite (y [error] dice por qué). */
    fun iniciar(): Boolean {
        if (grabador.state != AudioRecord.STATE_INITIALIZED) {
            error = "no se pudo abrir la captura de audio"
            grabador.release()
            return false
        }
        try {
            grabador.startRecording()
        } catch (e: IllegalStateException) {
            error = "no se pudo empezar a capturar (${e.message})"
            grabador.release()
            return false
        }
        if (grabador.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Puede negarse sin excepción.
            error = "Android no dejó empezar la captura"
            grabador.release()
            return false
        }
        activa = true
        hilo = Thread(::leer, "ParlanteY-captura").apply { start() }
        Log.i(Supervisor.TAG, "captura: ${grabador.sampleRate} Hz × ${grabador.channelCount}")
        return true
    }

    private fun leer() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(TASA / 100 * CANALES) // 10 ms
        while (activa) {
            val n = grabador.read(buf, 0, buf.size)
            if (n < 0) {
                if (activa) {
                    error = "la captura se cortó ($n)"
                    Log.w(Supervisor.TAG, "AudioRecord.read = $n")
                }
                cola.cerrar() // el envío ve el fin y termina solo
                break
            }
            if (n > 0) cola.escribir(buf, 0, n / CANALES)
        }
    }

    /** Detiene y libera (idempotente). La cola se cierra: el envío termina solo. */
    fun detener() {
        cola.cerrar()
        if (!activa && hilo == null) return
        activa = false
        try {
            grabador.stop() // desbloquea el read
        } catch (_: IllegalStateException) {
        }
        hilo?.join(500)
        hilo = null
        grabador.release()
    }

    companion object {
        const val TASA = 48000
        const val CANALES = 2
        /** Lo más que espera en la captura si el enlace se atrasa; más, se descarta lo más antiguo. */
        const val MAX_PENDIENTE_MS = 250L
    }
}

/** La captura como cola de una pista en vivo: al (re)abrirse, lo viejo se descarta. */
class FabricaCaptura(private val cola: ColaCaptura) : ControlCola.Fabrica {
    override fun cantidad(): Int = 1

    override fun abrir(indice: Int, desdeMs: Long): ControlCola.Apertura {
        cola.vaciar()
        return object : ControlCola.Apertura {
            override fun lista(): Boolean = true

            override fun fuente(): Fuente = cola

            override fun track(): Track = TRACK

            override fun error(): String? = null

            override fun cerrar() = Unit
        }
    }

    companion object {
        val TRACK = Track("Todo el audio", "", 0)
    }
}
