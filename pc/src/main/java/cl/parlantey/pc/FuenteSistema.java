package cl.parlantey.pc;

import cl.parlantey.core.ColaCaptura;
import java.io.IOException;

/** Lo que suena en el PC (WASAPI loopback en Windows; una señal sintética en los tests). */
public interface FuenteSistema {

    /** Empieza a capturar; el PCM16 llega a la cola devuelta. */
    ColaCaptura iniciar() throws IOException;

    /** Deja de capturar y restaura el silencio si lo había puesto. Idempotente. */
    void detener();

    /** Pide silenciar (true) o restaurar (false) la salida del PC. Se aplica en segundo plano. */
    void silenciar(boolean si);

    /** true si la salida del PC está silenciada por esta fuente. */
    boolean silenciado();

    /** «48 kHz float, 2 canales · Altavoces», o null si no captura. */
    String descripcion();

    /** Por qué dejó de capturar sola (null si no). */
    String error();
}
