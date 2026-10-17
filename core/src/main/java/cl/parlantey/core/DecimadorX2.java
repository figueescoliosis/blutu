package cl.parlantey.core;

/**
 * Decimación ×2 de PCM16 entrelazado: FIR paso bajo de media banda y se descarta 1 de cada 2
 * frames. FIR de 47 coeficientes (Kaiser, β = 7) en punto fijo Q15, diseñado con
 * {@code herramientas/disenar_mediabanda.py}: ≥ 69 dB de atenuación entre 0,3 y 0,5 fs y rizado
 * ≤ 0,003 dB entre 0 y 0,2 fs, con fs = tasa de entrada. Ganancia DC exactamente 1.
 *
 * <p>Guarda historial entre llamadas, así que se puede procesar por trozos de cualquier largo
 * (también impar). Solo enteros: salida idéntica en cualquier plataforma. No asigna memoria; no
 * es seguro para varios hilos.
 */
public final class DecimadorX2 {

    static final int TAPS = 47;
    private static final int MEDIO = (TAPS - 1) / 2;

    /** h[0], h[2], …, h[22]; h[46 − k] = h[k] y los h[k] impares son 0, salvo el central. */
    static final int[] LADO = {-3, 13, -35, 77, -148, 261, -433, 693, -1096, 1787, -3290, 10367};
    static final int CENTRO = 16382;

    private final int canales;
    /** Por canal, 2 × TAPS: cada muestra se escribe dos veces para leer la ventana sin módulo. */
    private final int[] linea;
    private int pos;
    private boolean impar;

    public DecimadorX2(int canales) {
        if (canales < 1) {
            throw new IllegalArgumentException("canales fuera de rango: " + canales);
        }
        this.canales = canales;
        this.linea = new int[canales * 2 * TAPS];
    }

    /** Olvida el historial, por ejemplo al cambiar de pista. */
    public void reiniciar() {
        for (int i = 0; i < linea.length; i++) {
            linea[i] = 0;
        }
        pos = 0;
        impar = false;
    }

    /**
     * Procesa {@code frames} frames de {@code entrada} (entrelazados) y escribe la salida
     * decimada en {@code salida} desde {@code salidaDesde}. Devuelve cuántos frames escribió:
     * {@code frames / 2}, o uno más o menos según la paridad acumulada.
     */
    public int procesar(short[] entrada, int desde, int frames, short[] salida, int salidaDesde) {
        final int[] lado = LADO;
        final int[] l = linea;
        int escritos = 0;
        for (int f = 0; f < frames; f++) {
            pos = pos == 0 ? TAPS - 1 : pos - 1;
            int base = desde + f * canales;
            for (int c = 0; c < canales; c++) {
                int v = entrada[base + c];
                int b = c * 2 * TAPS + pos;
                l[b] = v;
                l[b + TAPS] = v;
            }
            impar = !impar;
            if (impar) {
                continue;
            }
            int o = salidaDesde + escritos * canales;
            for (int c = 0; c < canales; c++) {
                // l[b + k] = x[n − k] para k = 0..46.
                int b = c * 2 * TAPS + pos;
                long acc = (long) CENTRO * l[b + MEDIO];
                for (int j = 0; j < lado.length; j++) {
                    acc += (long) lado[j] * (l[b + 2 * j] + l[b + TAPS - 1 - 2 * j]);
                }
                int y = (int) ((acc + 16384) >> 15);
                if (y > 32767) {
                    y = 32767;
                } else if (y < -32768) {
                    y = -32768;
                }
                salida[o + c] = (short) y;
            }
            escritos++;
        }
        return escritos;
    }

    /** Etapas ×2 necesarias para bajar {@code tasa} a 48 kHz o menos (88,2/96 → 1; 176,4/192 → 2). */
    public static int etapasPara(int tasa) {
        int etapas = 0;
        while (tasa > Protocolo.TASA_MAX) {
            tasa /= 2;
            etapas++;
        }
        return etapas;
    }
}
