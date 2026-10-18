package cl.parlantey.core;

/** PCM16 entrelazado con tasa y canales fijos: el tono de prueba, un archivo decodificado, la captura. */
public interface Fuente {

    /** Lo que devuelve {@link #leer} cuando la fuente terminó. */
    int FIN = -1;

    int tasa();

    int canales();

    /**
     * Escribe hasta {@code frames} frames en {@code destino} desde el frame {@code desdeFrame}
     * ({@code destino[(desdeFrame + i) * canales + c]}). Devuelve los frames escritos, 0 si por
     * ahora no hay datos, o {@link #FIN} si la fuente terminó. No debe bloquear.
     */
    int leer(short[] destino, int desdeFrame, int frames);
}
