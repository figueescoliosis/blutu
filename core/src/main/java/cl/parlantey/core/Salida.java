package cl.parlantey.core;

/**
 * Salida de audio PCM16 entrelazado. El receptor la implementa con AudioTrack (MODE_STREAM); los
 * tests, con una salida falsa. El contrato sigue al AudioTrack de Android 2.3:
 *
 * <ul>
 * <li>Solo un hilo (el de audio) llama a estos métodos.</li>
 * <li>{@link #escribir} devuelve los frames escritos, que pueden ser menos de los pedidos o 0 si
 * la salida no está sonando: nunca se asume que bloquea.</li>
 * <li>{@link #vaciar()} = pause() + flush(): descarta lo pendiente y la posición vuelve a 0. Un
 * flush() solo, con la pista sonando, no hace nada en 2.3.</li>
 * <li>{@link #detener()} = pause() + flush() + stop().</li>
 * <li>La reproducción no avanza hasta que el buffer está lleno (AudioFlinger, FS_FILLING).</li>
 * </ul>
 */
public interface Salida {

    /** Prepara la salida. Devuelve false si el sistema no la pudo crear. */
    boolean abrir(int tasa, int canales);

    /** Capacidad del buffer de la salida, en frames. */
    int capacidadFrames();

    /** Escribe hasta {@code frames} frames desde {@code pcm[desde]}; devuelve los frames escritos o un valor ≤ 0. */
    int escribir(short[] pcm, int desde, int frames);

    /** Frames reproducidos desde que se abrió o se vació (contador de 32 bits; puede dar la vuelta). */
    int framesReproducidos();

    void reproducir();

    void pausar();

    void vaciar();

    void detener();

    /** Libera la salida; después hay que volver a {@link #abrir}. */
    void cerrar();
}
