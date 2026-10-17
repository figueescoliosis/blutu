package cl.parlantey.core;

/**
 * Aritmética del número de secuencia de AUDIO (u32 que da la vuelta). El emisor empieza en 0 y
 * suma 1 por cada AUDIO; el receptor informa en STATE el último que recibió.
 */
public final class Seq {

    private Seq() {
    }

    public static long siguiente(long seq) {
        return (seq + 1) & 0xFFFFFFFFL;
    }

    /**
     * Bloques enviados que el receptor aún no ha recibido: {@code enviado − ultimo} en aritmética
     * u32, como entero con signo. Vale aunque el contador haya dado la vuelta; con
     * {@code ultimo = SEQ_NINGUNO} y {@code enviado = 0} da 1.
     */
    public static int distancia(long enviado, long ultimo) {
        return (int) (enviado - ultimo);
    }
}
