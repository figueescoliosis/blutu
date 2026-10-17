package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lee tramas {@code magic u16 | tipo u8 | flags u8 | largo u32 | payload} de un flujo.
 *
 * <p>No asigna memoria por trama: la cabecera y el payload se leen en buffers creados una
 * sola vez, y el payload se expone con un {@link DataInputStream} que se rearma en cada
 * lectura. Leer del payload más de lo que trae lanza {@link ProtocoloException}. Lo devuelto por {@link #datos()} solo es válido hasta la siguiente llamada a
 * {@link #leer()}. No es seguro para varios hilos: una conexión tiene un solo lector.
 */
public final class LectorTramas {

    private final InputStream entrada;
    private final byte[] cabecera = new byte[Protocolo.LARGO_CABECERA];
    private final BufferPayload payload = new BufferPayload(new byte[Protocolo.MAX_PAYLOAD]);
    private final DataInputStream datos = new DataInputStream(payload);

    private int tipo;
    private int flags;
    private int largo;
    private long nanosCabecera;

    public LectorTramas(InputStream entrada) {
        this.entrada = entrada;
    }

    /**
     * Bloquea hasta tener una trama completa y devuelve su tipo.
     *
     * @throws ProtocoloException si el magic es inválido o el largo supera {@link Protocolo#MAX_PAYLOAD}
     * @throws EOFException si el flujo termina, entre tramas o a mitad de una
     */
    public int leer() throws IOException {
        leerCompleto(cabecera, Protocolo.LARGO_CABECERA);
        nanosCabecera = System.nanoTime();

        int magic = ((cabecera[0] & 0xFF) << 8) | (cabecera[1] & 0xFF);
        if (magic != Protocolo.MAGIC) {
            throw new ProtocoloException("magic inválido: 0x" + Integer.toHexString(magic));
        }
        long largoLeido = (((long) (cabecera[4] & 0xFF)) << 24)
                | ((cabecera[5] & 0xFF) << 16)
                | ((cabecera[6] & 0xFF) << 8)
                | (cabecera[7] & 0xFF);
        if (largoLeido > Protocolo.MAX_PAYLOAD) {
            throw new ProtocoloException("largo excesivo: " + largoLeido);
        }
        tipo = cabecera[2] & 0xFF;
        flags = cabecera[3] & 0xFF;
        largo = (int) largoLeido;

        leerCompleto(payload.arreglo(), largo);
        payload.rearmar(largo);
        return tipo;
    }

    /** Tipo de la última trama leída. */
    public int tipo() {
        return tipo;
    }

    /** Flags de la última trama leída. */
    public int flags() {
        return flags;
    }

    /** Largo del payload de la última trama leída. */
    public int largo() {
        return largo;
    }

    /** {@link System#nanoTime()} al terminar de llegar la cabecera de la última trama. */
    public long nanosCabecera() {
        return nanosCabecera;
    }

    /** Payload de la última trama leída. Válido hasta la siguiente llamada a {@link #leer()}. */
    public DataInputStream datos() {
        return datos;
    }

    private void leerCompleto(byte[] destino, int n) throws IOException {
        int leidos = 0;
        while (leidos < n) {
            int r = entrada.read(destino, leidos, n - leidos);
            if (r < 0) {
                throw new EOFException(destino == cabecera && leidos == 0
                        ? "conexión cerrada"
                        : "el flujo terminó a mitad de una trama");
            }
            leidos += r;
        }
    }

    /**
     * Payload de la trama actual sobre un arreglo fijo que se rearma sin asignar. Leer más
     * allá del final es un error de protocolo (payload más corto que el mensaje), no un EOF
     * de la conexión.
     */
    private static final class BufferPayload extends InputStream {

        private final byte[] arreglo;
        private int pos;
        private int count;

        BufferPayload(byte[] arreglo) {
            this.arreglo = arreglo;
        }

        byte[] arreglo() {
            return arreglo;
        }

        void rearmar(int n) {
            pos = 0;
            count = n;
        }

        @Override
        public int read() throws IOException {
            if (pos >= count) {
                throw payloadCorto();
            }
            return arreglo[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] destino, int desde, int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            if (pos >= count) {
                throw payloadCorto();
            }
            int k = Math.min(n, count - pos);
            System.arraycopy(arreglo, pos, destino, desde, k);
            pos += k;
            return k;
        }

        @Override
        public long skip(long n) {
            long k = Math.max(0L, Math.min(n, count - pos));
            pos += (int) k;
            return k;
        }

        @Override
        public int available() {
            return count - pos;
        }

        private static ProtocoloException payloadCorto() {
            return new ProtocoloException("payload más corto que el mensaje");
        }
    }
}
