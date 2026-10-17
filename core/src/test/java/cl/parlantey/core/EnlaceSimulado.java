package cl.parlantey.core;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Un sentido de un enlace simulado con tiempo virtual: lo escrito queda "en tránsito" (el buffer
 * del socket del emisor) y se entrega a {@code bytesPorSegundo} a medida que avanza el tiempo, salvo
 * mientras esté {@link #pausado}. Para usarlo en un solo hilo: quien lee solo lo hace si
 * {@link #tramaCompleta()}, y escribir más de lo que cabe en el buffer del socket (bloquearía) es
 * un error.
 */
final class EnlaceSimulado {

    private final byte[] anillo;
    private final long bytesPorSegundo;
    private final int maxEnTransito;
    private int inicio;
    private int entregados;
    private int enTransito;
    private long credito; // bytes·1e9

    boolean pausado;
    int maxEnTransitoVisto;
    long totalEntregado;

    final OutputStream salida = new OutputStream() {
        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int desde, int n) {
            if (enTransito + n > maxEnTransito) {
                throw new IllegalStateException("escribir " + n + " bytes con " + enTransito
                        + " en tránsito bloquearía al emisor");
            }
            int fin = inicio + entregados + enTransito;
            for (int i = 0; i < n; i++) {
                anillo[(fin + i) % anillo.length] = b[desde + i];
            }
            enTransito += n;
            maxEnTransitoVisto = Math.max(maxEnTransitoVisto, enTransito);
        }
    };

    final InputStream entrada = new InputStream() {
        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int desde, int n) {
            if (n == 0) {
                return 0;
            }
            if (entregados == 0) {
                throw new IllegalStateException("leer sin datos bloquearía");
            }
            int k = Math.min(n, entregados);
            for (int i = 0; i < k; i++) {
                b[desde + i] = anillo[(inicio + i) % anillo.length];
            }
            inicio = (inicio + k) % anillo.length;
            entregados -= k;
            return k;
        }
    };

    /**
     * @param maxEnTransito buffer del socket del lado que escribe (≈ 1,5 MB medido en la Fase 0)
     */
    EnlaceSimulado(long bytesPorSegundo, int maxEnTransito) {
        this.bytesPorSegundo = bytesPorSegundo;
        this.maxEnTransito = maxEnTransito;
        this.anillo = new byte[maxEnTransito + 2 * (Protocolo.LARGO_CABECERA + Protocolo.MAX_PAYLOAD)];
    }

    void avanzar(long nanos) {
        if (pausado) {
            return;
        }
        credito += nanos * bytesPorSegundo;
        int k = (int) Math.min(enTransito, credito / 1000000000L);
        if (k == enTransito) {
            credito = 0; // sin cola: el crédito no se acumula
        } else {
            credito -= k * 1000000000L;
        }
        enTransito -= k;
        entregados += k;
        totalEntregado += k;
    }

    /** true si ya llegó al menos una trama completa (cabecera + payload). */
    boolean tramaCompleta() {
        if (entregados < Protocolo.LARGO_CABECERA) {
            return false;
        }
        long largo = 0;
        for (int i = 4; i < 8; i++) {
            largo = (largo << 8) | (anillo[(inicio + i) % anillo.length] & 0xFF);
        }
        // Una cabecera inválida se deja leer: el lector la rechaza.
        return largo > Protocolo.MAX_PAYLOAD || entregados >= Protocolo.LARGO_CABECERA + largo;
    }

    int enTransito() {
        return enTransito;
    }
}
