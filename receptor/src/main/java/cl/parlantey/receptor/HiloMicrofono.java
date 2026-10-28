package cl.parlantey.receptor;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

import cl.parlantey.core.CodificadorIma;
import cl.parlantey.core.Config;
import cl.parlantey.core.EscritorTramas;
import cl.parlantey.core.Protocolo;

/**
 * App Mic ({@code BuildConfig.MICROFONO}): durante una conexión graba el micrófono (el del audífono
 * si es de 4 polos: Android cambia solo) y lo manda al emisor como MIC_CONFIG + MIC_AUDIO (v4):
 * 16 kHz mono, bloques IMA ADPCM de 256 muestras (16 ms). Sin asignaciones en el bucle.
 *
 * <p>Si el enlace se atrasa, {@code write} bloquea y el AudioRecord pierde lo más viejo: la latencia
 * no crece. El emisor la acota con la compensación de deriva del {@link cl.parlantey.core.Reproductor}.
 */
final class HiloMicrofono extends Thread {

    static final int SIN_CONEXION = 0;
    static final int ENVIANDO = 1;
    static final int ERROR = 2;

    /** Interruptor de la pantalla. Apagado sigue leyendo (sin atraso al volver), pero no envía. */
    static volatile boolean enviar = true;
    static volatile int estado = SIN_CONEXION;
    /** Pico del último bloque, 0..32767 (para la pantalla). */
    static volatile int pico;

    private final EscritorTramas escritor;
    private volatile boolean sigue = true;

    HiloMicrofono(EscritorTramas escritor) {
        super("ParlanteY-microfono");
        this.escritor = escritor;
    }

    /** Espera hasta 500 ms a que suelte el AudioRecord: la próxima conexión abre otro. */
    void detener() {
        sigue = false;
        interrupt();
        try {
            join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Solo se crea con BuildConfig.MICROFONO, y RECORD_AUDIO está en el manifiesto del flavor mic. */
    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int mpb = Protocolo.MUESTRAS_MIC;
        int minimo = AudioRecord.getMinBufferSize(Protocolo.TASA_MIC, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord grabadora = null;
        boolean grabando = false;
        try {
            if (minimo <= 0) {
                throw new IOException("16 kHz mono no soportado (" + minimo + ")");
            }
            grabadora = new AudioRecord(MediaRecorder.AudioSource.MIC, Protocolo.TASA_MIC,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimo, 4 * mpb * 2));
            if (grabadora.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord no se pudo crear (¿otra app usa el micrófono?)");
            }
            Config config = new Config(Protocolo.CODEC_IMA_ADPCM, Protocolo.TASA_MIC, 1, mpb,
                    Protocolo.OBJETIVO_MIC_MS, true);
            CodificadorIma codificador = new CodificadorIma(1, mpb);
            short[] pcm = new short[mpb];
            byte[] bloque = new byte[codificador.bytesBloque()];
            escritor.micConfig(config);
            grabadora.startRecording();
            grabando = true;
            estado = ENVIANDO;
            Log.i(Registro.TAG, "micrófono: buffer " + Math.max(minimo, 4 * mpb * 2) + " bytes (mínimo " + minimo + ")");
            long seq = 0;
            while (sigue) {
                int n = 0;
                while (n < mpb && sigue) {
                    int leidas = grabadora.read(pcm, n, mpb - n);
                    if (leidas < 0) {
                        throw new IOException("AudioRecord.read devolvió " + leidas);
                    }
                    n += leidas;
                }
                if (!sigue) {
                    break;
                }
                int max = 0;
                for (int i = 0; i < mpb; i++) {
                    int v = pcm[i] < 0 ? -pcm[i] : pcm[i];
                    if (v > max) {
                        max = v;
                    }
                }
                pico = Math.min(max, 32767);
                if (enviar) {
                    codificador.codificarBloque(pcm, 0, mpb, bloque, 0);
                    escritor.micAudio(seq, bloque, 0, bloque.length);
                    seq = (seq + 1) & 0xFFFFFFFFL;
                }
            }
            estado = SIN_CONEXION;
        } catch (IOException e) {
            // Grabando, es la conexión que se cortó (el hilo de red se entera solo); si no, el micrófono no abrió.
            estado = grabando ? SIN_CONEXION : ERROR;
            if (!grabando) {
                Log.w(Registro.TAG, "micrófono: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            estado = ERROR;
            Log.w(Registro.TAG, "micrófono", e);
        } finally {
            pico = 0;
            if (grabadora != null) {
                if (grabando) {
                    grabadora.stop();
                }
                grabadora.release();
            }
        }
    }
}
