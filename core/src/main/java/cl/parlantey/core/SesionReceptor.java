package cl.parlantey.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lado receptor (servidor) de una conexión en la Fase 0: envía HELLO, responde PING con PONG,
 * cuenta los TEST_DATA y contesta TEST_RESULT al recibir el último. El bucle no asigna memoria
 * por trama.
 */
public final class SesionReceptor {

    /** Avisos al dueño de la sesión. Se llaman desde el hilo que ejecuta {@link #atender}. */
    public interface Oyente {
        void pruebaTerminada(long bytesRecibidos, long duracionMs);
    }

    private SesionReceptor() {
    }

    /**
     * Atiende una conexión hasta que el emisor envía BYE.
     *
     * @return el código del BYE recibido
     * @throws java.io.EOFException si la conexión se corta sin BYE
     * @throws ProtocoloException si llega una trama inválida (hay que cerrar la conexión)
     */
    public static int atender(InputStream entrada, OutputStream salida, Hello hello, Oyente oyente)
            throws IOException {
        LectorTramas lector = new LectorTramas(entrada);
        EscritorTramas escritor = new EscritorTramas(salida);
        escritor.hello(hello);

        boolean enPrueba = false;
        long bytes = 0;
        long inicioNs = 0;
        while (true) {
            switch (lector.leer()) {
                case Protocolo.PING:
                    escritor.pong(lector.datos().readLong());
                    break;
                case Protocolo.TEST_DATA:
                    if (!enPrueba) {
                        enPrueba = true;
                        bytes = 0;
                        inicioNs = lector.nanosCabecera();
                    }
                    bytes += Protocolo.LARGO_CABECERA + lector.largo();
                    if ((lector.flags() & Protocolo.FLAG_ULTIMA) != 0) {
                        enPrueba = false;
                        long duracionMs = (System.nanoTime() - inicioNs) / 1000000L;
                        escritor.testResult(bytes, duracionMs);
                        if (oyente != null) {
                            oyente.pruebaTerminada(bytes, duracionMs);
                        }
                    }
                    break;
                case Protocolo.BYE:
                    return lector.datos().readUnsignedByte();
                default:
                    // Tipo desconocido o que esta fase no usa: el payload ya se consumió.
                    break;
            }
        }
    }
}
