package cl.parlantey.core;

/**
 * IMA ADPCM de ParlanteY (PROTOCOLO.md §6): tablas estándar y decodificador.
 *
 * <p>Bloque de {@code canales × (4 + mpb/2)} bytes. Primero, por canal, el estado del códec
 * <em>antes</em> de la primera muestra: {@code predictor i16} big-endian, {@code índice u8} y un
 * byte reservado (0). Después, por canal (planar), {@code mpb/2} bytes con una muestra por nibble;
 * el nibble bajo es la muestra anterior. A diferencia del IMA de WAV de Microsoft, la cabecera no
 * cuenta como muestra: las {@code mpb} muestras van como nibbles.
 *
 * <p>Solo enteros: la salida es idéntica en cualquier JVM o Dalvik/ART. No asigna memoria.
 */
public final class ImaAdpcm {

    /** Bytes de cabecera por canal: predictor i16, índice u8, reservado u8. */
    public static final int CABECERA_CANAL = 4;

    /** Tabla estándar de pasos (89 valores). */
    static final int[] PASOS = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
            1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
            8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767};

    /** Tabla estándar de ajuste del índice, por nibble. */
    static final int[] AJUSTE_INDICE = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

    public static final int INDICE_MAX = 88;

    private ImaAdpcm() {
    }

    public static int bytesBloque(int canales, int muestrasPorBloque) {
        return canales * (CABECERA_CANAL + muestrasPorBloque / 2);
    }

    /**
     * Decodifica un bloque a PCM16 entrelazado: la muestra {@code i} del canal {@code c} queda en
     * {@code pcm[pcmDesde + i * canales + c]}, el formato de {@code AudioTrack.write(short[], …)}.
     * Un índice mayor que 88 en la cabecera se limita a 88: nunca lanza por datos corruptos.
     */
    public static void decodificarBloque(byte[] bloque, int desde, int canales, int muestrasPorBloque,
            short[] pcm, int pcmDesde) {
        int datos = desde + CABECERA_CANAL * canales;
        int bytesCanal = muestrasPorBloque >> 1;
        for (int c = 0; c < canales; c++) {
            int h = desde + CABECERA_CANAL * c;
            int predictor = (short) (((bloque[h] & 0xFF) << 8) | (bloque[h + 1] & 0xFF));
            int indice = Math.min(bloque[h + 2] & 0xFF, INDICE_MAX);
            decodificarCanal(bloque, datos + c * bytesCanal, muestrasPorBloque, predictor, indice,
                    pcm, pcmDesde + c, canales);
        }
    }

    /**
     * Decodifica {@code muestras} nibbles de un canal a partir del estado dado y escribe una
     * muestra cada {@code salto} posiciones de {@code pcm}. Devuelve el estado final empaquetado:
     * {@code predictor << 8 | índice} (ver {@link #predictorDe} e {@link #indiceDe}).
     */
    public static int decodificarCanal(byte[] datos, int desde, int muestras, int predictor, int indice,
            short[] pcm, int pcmDesde, int salto) {
        final int[] pasos = PASOS;
        final int[] ajuste = AJUSTE_INDICE;
        int o = pcmDesde;
        for (int i = 0; i < muestras; i++) {
            int b = datos[desde + (i >> 1)];
            int n = (i & 1) == 0 ? b & 0x0F : (b >> 4) & 0x0F;

            int paso = pasos[indice];
            int dif = paso >> 3;
            if ((n & 4) != 0) {
                dif += paso;
            }
            if ((n & 2) != 0) {
                dif += paso >> 1;
            }
            if ((n & 1) != 0) {
                dif += paso >> 2;
            }
            if ((n & 8) != 0) {
                predictor -= dif;
                if (predictor < -32768) {
                    predictor = -32768;
                }
            } else {
                predictor += dif;
                if (predictor > 32767) {
                    predictor = 32767;
                }
            }
            indice += ajuste[n];
            if (indice < 0) {
                indice = 0;
            } else if (indice > INDICE_MAX) {
                indice = INDICE_MAX;
            }
            pcm[o] = (short) predictor;
            o += salto;
        }
        return (predictor << 8) | indice;
    }

    public static int predictorDe(int estado) {
        return estado >> 8;
    }

    public static int indiceDe(int estado) {
        return estado & 0xFF;
    }
}
