package cl.parlantey.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lado emisor (cliente) de una conexión: espera el HELLO, mide RTT y throughput y se despide.
 * Todos los métodos bloquean; un mismo hilo debe usar la sesión. Para cortar una espera
 * bloqueada, cerrar el socket desde otro hilo.
 */
public final class SesionEmisor {

    /** Payload de cada TEST_DATA durante la prueba de enlace. */
    public static final int LARGO_TRAMA_PRUEBA = 4096;

    private final LectorTramas lector;
    private final EscritorTramas escritor;

    public SesionEmisor(InputStream entrada, OutputStream salida) {
        this.lector = new LectorTramas(entrada);
        this.escritor = new EscritorTramas(salida);
    }

    /**
     * Espera el HELLO del receptor (debe ser la primera trama).
     *
     * @throws VersionIncompatibleException si la versión no coincide; antes de lanzarla
     *         se envía BYE con {@link Protocolo#BYE_VERSION}
     */
    public Hello esperarHello() throws IOException {
        int tipo = lector.leer();
        if (tipo == Protocolo.BYE) {
            throw new ByeRecibidoException(lector.datos().readUnsignedByte());
        }
        if (tipo != Protocolo.HELLO) {
            throw new ProtocoloException("se esperaba HELLO y llegó el tipo 0x" + Integer.toHexString(tipo));
        }
        try {
            return Hello.leer(lector.datos());
        } catch (VersionIncompatibleException e) {
            try {
                escritor.bye(Protocolo.BYE_VERSION);
            } catch (IOException ignorada) {
                // La conexión se cierra igual; lo importante es informar la incompatibilidad.
            }
            throw e;
        }
    }

    /** Envía un PING y espera su PONG. Devuelve el RTT en nanosegundos. */
    public long ping() throws IOException {
        long t = System.nanoTime();
        escritor.ping(t);
        while (true) {
            esperar(Protocolo.PONG);
            // Un PONG con otro timestamp es de un PING anterior: se descarta.
            if (lector.datos().readLong() == t) {
                return System.nanoTime() - t;
            }
        }
    }

    /**
     * Prueba de enlace: {@code pings} PING/PONG y luego TEST_DATA durante {@code duracionMs}
     * con tramas de {@code largoTrama} bytes de payload; la última lleva {@link Protocolo#FLAG_ULTIMA}.
     */
    public ResultadoPrueba probarEnlace(int pings, long duracionMs, int largoTrama) throws IOException {
        if (pings < 1) {
            throw new IllegalArgumentException("se necesita al menos un ping");
        }
        long min = Long.MAX_VALUE;
        long max = 0;
        long suma = 0;
        for (int i = 0; i < pings; i++) {
            long rtt = ping();
            min = Math.min(min, rtt);
            max = Math.max(max, rtt);
            suma += rtt;
        }

        long bytesTrama = Protocolo.LARGO_CABECERA + largoTrama;
        long enviados = 0;
        long inicio = System.nanoTime();
        long fin = inicio + duracionMs * 1000000L;
        while (System.nanoTime() - fin < 0) {
            escritor.testData(largoTrama, false);
            enviados += bytesTrama;
        }
        escritor.testData(largoTrama, true);
        enviados += bytesTrama;

        esperar(Protocolo.TEST_RESULT);
        long bytesRecibidos = lector.datos().readLong();
        long duracionReceptorMs = lector.datos().readInt() & 0xFFFFFFFFL;
        long duracionEmisorMs = (System.nanoTime() - inicio) / 1000000L;

        return new ResultadoPrueba(pings, min, suma / pings, max,
                enviados, bytesRecibidos, duracionReceptorMs, duracionEmisorMs);
    }

    /** Envía BYE. No cierra el flujo: eso le toca a quien abrió el socket. */
    public void despedir(int codigo) throws IOException {
        escritor.bye(codigo);
    }

    /** Lee tramas hasta una del tipo pedido, ignorando las demás. BYE corta la espera. */
    private void esperar(int tipoEsperado) throws IOException {
        while (true) {
            int tipo = lector.leer();
            if (tipo == tipoEsperado) {
                return;
            }
            if (tipo == Protocolo.BYE) {
                throw new ByeRecibidoException(lector.datos().readUnsignedByte());
            }
        }
    }
}
