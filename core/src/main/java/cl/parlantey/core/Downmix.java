package cl.parlantey.core;

/** Mezcla a mono: promedio de canales con saturación. Solo enteros; no asigna memoria. */
public final class Downmix {

    /** MediaCodec puede entregar hasta 7.1. */
    public static final int MAX_CANALES_ENTRADA = 8;

    private Downmix() {
    }

    /**
     * Promedia los {@code canales} de cada frame de {@code entrada} (entrelazado) y escribe un
     * frame mono por cada uno. Redondea al entero más cercano, con los empates alejándose de
     * cero. Puede trabajar en el mismo arreglo ({@code salida == entrada}, {@code salidaDesde ==
     * desde}): cada frame se lee antes de escribir su resultado.
     */
    public static void aMono(short[] entrada, int desde, int frames, int canales, short[] salida, int salidaDesde) {
        if (canales < 1 || canales > MAX_CANALES_ENTRADA) {
            throw new IllegalArgumentException("canales fuera de rango: " + canales);
        }
        int mitad = canales / 2;
        for (int f = 0; f < frames; f++) {
            int base = desde + f * canales;
            int suma = 0;
            for (int c = 0; c < canales; c++) {
                suma += entrada[base + c];
            }
            int y = suma >= 0 ? (suma + mitad) / canales : -((-suma + mitad) / canales);
            if (y > 32767) {
                y = 32767;
            } else if (y < -32768) {
                y = -32768;
            }
            salida[salidaDesde + f] = (short) y;
        }
    }
}
