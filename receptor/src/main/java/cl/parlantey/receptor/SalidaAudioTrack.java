package cl.parlantey.receptor;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import cl.parlantey.core.Salida;

/**
 * {@link Salida} sobre AudioTrack (MODE_STREAM, STREAM_MUSIC, PCM 16 bits) con un buffer de
 * 2 × {@code getMinBufferSize}. Solo la usa el hilo de audio. Sigue el contrato de 2.3 de
 * {@link Salida}: vaciar = pause + flush, detener = pause + flush + stop.
 */
final class SalidaAudioTrack implements Salida {

    /**
     * 2 desde la 0.4.2 (antes 4): en el Galaxy Y, 4 × son 371 ms a 48 kHz y eran el piso de la
     * latencia en vivo (NOTAS.md, «Latencia elegible»).
     */
    static final int FACTOR_BUFFER = 2;

    private Pista pista;
    private int canales;
    private int capacidad;

    /** Para la UI y NOTAS (las escribe el hilo de audio). */
    volatile int minBufferBytes;
    volatile int capacidadFramesPublicada;

    @Override
    public boolean abrir(int tasa, int canales) {
        int formato = canales == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(tasa, formato, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(Registro.TAG, "getMinBufferSize(" + tasa + ", " + canales + ") = " + min);
            return false;
        }
        int bytes = FACTOR_BUFFER * min;
        Pista p;
        try {
            p = new Pista(tasa, formato, bytes);
        } catch (IllegalArgumentException e) {
            Log.w(Registro.TAG, "AudioTrack rechazó " + tasa + " Hz × " + canales, e);
            return false;
        }
        if (p.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(Registro.TAG, "AudioTrack no inicializado: " + tasa + " Hz × " + canales);
            p.release();
            return false;
        }
        pista = p;
        this.canales = canales;
        // El tamaño real del buffer: AudioFlinger no suena hasta llenarlo, y el reproductor
        // detecta una salida trabada cuando está lleno y la posición no avanza.
        int nativos = p.framesNativos();
        this.capacidad = nativos > 0 ? nativos : bytes / (2 * canales);
        minBufferBytes = min;
        capacidadFramesPublicada = capacidad;
        Log.i(Registro.TAG, "AudioTrack " + tasa + " Hz × " + canales + ": getMinBufferSize " + min
                + " B, buffer pedido " + bytes + " B, real " + capacidad + " frames ("
                + (capacidad * 1000L / tasa) + " ms)");
        return true;
    }

    @Override
    public int capacidadFrames() {
        return capacidad;
    }

    @Override
    public int escribir(short[] pcm, int desde, int frames) {
        int n = pista.write(pcm, desde, frames * canales);
        return n > 0 ? n / canales : n;
    }

    @Override
    public int framesReproducidos() {
        return pista.getPlaybackHeadPosition();
    }

    @Override
    public void reproducir() {
        try {
            pista.play();
        } catch (IllegalStateException e) {
            Log.w(Registro.TAG, "play()", e);
        }
    }

    @Override
    public void pausar() {
        try {
            pista.pause();
        } catch (IllegalStateException e) {
            Log.w(Registro.TAG, "pause()", e);
        }
    }

    @Override
    public void vaciar() {
        try {
            pista.pause(); // en 2.3, flush() con la pista sonando no hace nada
            pista.flush();
        } catch (IllegalStateException e) {
            Log.w(Registro.TAG, "vaciar()", e);
        }
    }

    @Override
    public void detener() {
        try {
            pista.pause();
            pista.flush();
            pista.stop();
        } catch (IllegalStateException e) {
            Log.w(Registro.TAG, "detener()", e);
        }
    }

    @Override
    public void cerrar() {
        if (pista != null) {
            pista.release();
            pista = null;
        }
    }

    /** AudioTrack de 2.3 con acceso a su frameCount nativo (protegido desde API 3). */
    private static final class Pista extends AudioTrack {
        @SuppressWarnings("deprecation") // el constructor de 2.3 (AudioTrack.Builder es de API 23)
        Pista(int tasa, int formato, int bytes) {
            super(AudioManager.STREAM_MUSIC, tasa, formato, AudioFormat.ENCODING_PCM_16BIT, bytes, MODE_STREAM);
        }

        @SuppressWarnings("deprecation")
        int framesNativos() {
            return getNativeFrameCount();
        }
    }

    /** getMinBufferSize y la tasa nativa, para NOTAS.md. */
    static String diagnostico() {
        StringBuilder sb = new StringBuilder("tasa nativa ")
                .append(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)).append(" Hz; getMinBufferSize:");
        int[] tasas = {44100, 48000};
        for (int tasa : tasas) {
            sb.append(' ').append(tasa).append(" mono ")
                    .append(AudioTrack.getMinBufferSize(tasa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .append(" B, estéreo ")
                    .append(AudioTrack.getMinBufferSize(tasa, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT))
                    .append(" B;");
        }
        return sb.toString();
    }
}
