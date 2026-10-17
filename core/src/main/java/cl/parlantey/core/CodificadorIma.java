package cl.parlantey.core;

/**
 * Codificador IMA ADPCM con estado por canal entre bloques. Cada bloque lleva en su cabecera el
 * estado con que empieza, y el estado se actualiza con la misma reconstrucción que usa
 * {@link ImaAdpcm#decodificarCanal}: decodificar un bloque aislado o en secuencia da lo mismo.
 * No asigna memoria; no es seguro para varios hilos.
 */
public final class CodificadorIma {

    private final int canales;
    private final int muestrasPorBloque;
    private final int[] predictor;
    private final int[] indice;

    public CodificadorIma(int canales, int muestrasPorBloque) {
        if (canales < 1 || canales > Protocolo.MAX_CANALES) {
            throw new IllegalArgumentException("canales fuera de rango: " + canales);
        }
        if (muestrasPorBloque < 2 || muestrasPorBloque % 2 != 0) {
            throw new IllegalArgumentException("muestrasPorBloque debe ser par: " + muestrasPorBloque);
        }
        this.canales = canales;
        this.muestrasPorBloque = muestrasPorBloque;
        this.predictor = new int[canales];
        this.indice = new int[canales];
    }

    /** Vuelve al estado inicial (predictor 0, índice 0), por ejemplo al empezar una pista. */
    public void reiniciar() {
        for (int c = 0; c < canales; c++) {
            predictor[c] = 0;
            indice[c] = 0;
        }
    }

    public int bytesBloque() {
        return ImaAdpcm.bytesBloque(canales, muestrasPorBloque);
    }

    /**
     * Codifica un bloque desde PCM16 entrelazado ({@code pcm[desde + i * canales + c]}). Lee solo
     * {@code muestrasValidas} frames; el resto del bloque se codifica como silencio (0), para el
     * último bloque de una pista. Escribe {@link #bytesBloque()} bytes en {@code destino}.
     */
    public void codificarBloque(short[] pcm, int desde, int muestrasValidas, byte[] destino, int destinoDesde) {
        if (muestrasValidas < 0 || muestrasValidas > muestrasPorBloque) {
            throw new IllegalArgumentException("muestrasValidas fuera de rango: " + muestrasValidas);
        }
        final int[] pasos = ImaAdpcm.PASOS;
        final int[] ajuste = ImaAdpcm.AJUSTE_INDICE;
        int bytesCanal = muestrasPorBloque >> 1;
        int datos = destinoDesde + ImaAdpcm.CABECERA_CANAL * canales;

        for (int c = 0; c < canales; c++) {
            int pred = predictor[c];
            int ind = indice[c];

            int h = destinoDesde + ImaAdpcm.CABECERA_CANAL * c;
            destino[h] = (byte) (pred >> 8);
            destino[h + 1] = (byte) pred;
            destino[h + 2] = (byte) ind;
            destino[h + 3] = 0;

            int salida = datos + c * bytesCanal;
            int byteActual = 0;
            for (int i = 0; i < muestrasPorBloque; i++) {
                int x = i < muestrasValidas ? pcm[desde + i * canales + c] : 0;

                // Cuantizador IMA de referencia.
                int paso = pasos[ind];
                int diff = x - pred;
                int n = 0;
                if (diff < 0) {
                    n = 8;
                    diff = -diff;
                }
                if (diff >= paso) {
                    n |= 4;
                    diff -= paso;
                }
                if (diff >= (paso >> 1)) {
                    n |= 2;
                    diff -= paso >> 1;
                }
                if (diff >= (paso >> 2)) {
                    n |= 1;
                }

                // Misma reconstrucción que el decodificador.
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
                    pred -= dif;
                    if (pred < -32768) {
                        pred = -32768;
                    }
                } else {
                    pred += dif;
                    if (pred > 32767) {
                        pred = 32767;
                    }
                }
                ind += ajuste[n];
                if (ind < 0) {
                    ind = 0;
                } else if (ind > ImaAdpcm.INDICE_MAX) {
                    ind = ImaAdpcm.INDICE_MAX;
                }

                if ((i & 1) == 0) {
                    byteActual = n;
                } else {
                    destino[salida + (i >> 1)] = (byte) (byteActual | (n << 4));
                }
            }
            predictor[c] = pred;
            indice[c] = ind;
        }
    }
}
