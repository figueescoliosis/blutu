package cl.parlantey.core;

/**
 * Resultado de una prueba de enlace: RTT por PING/PONG y throughput por TEST_DATA.
 * KB = 1000 bytes, igual que en la tabla de presets; bytes por milisegundo = KB/s.
 */
public final class ResultadoPrueba {

    public final int pings;
    public final long rttMinNs;
    public final long rttPromNs;
    public final long rttMaxNs;

    /** Bytes de trama completos (cabecera + payload) enviados por el emisor. */
    public final long bytesEnviados;
    /** Bytes que el receptor dice haber recibido (TEST_RESULT). */
    public final long bytesRecibidos;
    /** Duración medida por el receptor (TEST_RESULT). */
    public final long duracionReceptorMs;
    /** Desde el primer TEST_DATA hasta recibir TEST_RESULT, medido en el emisor (incluye un RTT). */
    public final long duracionEmisorMs;

    public ResultadoPrueba(int pings, long rttMinNs, long rttPromNs, long rttMaxNs,
            long bytesEnviados, long bytesRecibidos, long duracionReceptorMs, long duracionEmisorMs) {
        this.pings = pings;
        this.rttMinNs = rttMinNs;
        this.rttPromNs = rttPromNs;
        this.rttMaxNs = rttMaxNs;
        this.bytesEnviados = bytesEnviados;
        this.bytesRecibidos = bytesRecibidos;
        this.duracionReceptorMs = duracionReceptorMs;
        this.duracionEmisorMs = duracionEmisorMs;
    }

    /** Throughput según el receptor, en KB/s. Es la cifra de referencia. */
    public double kBpsReceptor() {
        return bytesRecibidos / (double) Math.max(1L, duracionReceptorMs);
    }

    /** Throughput según el emisor, en KB/s (algo menor: incluye la espera del TEST_RESULT). */
    public double kBpsEmisor() {
        return bytesRecibidos / (double) Math.max(1L, duracionEmisorMs);
    }

    public double rttMinMs() {
        return rttMinNs / 1e6;
    }

    public double rttPromMs() {
        return rttPromNs / 1e6;
    }

    public double rttMaxMs() {
        return rttMaxNs / 1e6;
    }
}
