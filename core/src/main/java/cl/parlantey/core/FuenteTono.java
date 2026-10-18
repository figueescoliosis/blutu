package cl.parlantey.core;

/**
 * Tono de prueba: seno de 440 Hz a −10 dBFS, igual en todos los canales. Se genera bajo demanda
 * desde una tabla con un número entero de ciclos (1200 muestras a 48 kHz, 2205 a 44,1 kHz), así que
 * no se desafina ni acumula error de fase. Nunca termina. No asigna memoria al leer.
 */
public final class FuenteTono implements Fuente {

    public static final int FRECUENCIA_HZ = 440;
    public static final double NIVEL_DBFS = -10;

    private final int tasa;
    private final int canales;
    private final short[] tabla;
    private int pos;

    public FuenteTono(int tasa, int canales) {
        if (tasa < Protocolo.TASA_MIN || tasa > 4 * Protocolo.TASA_MAX) {
            throw new IllegalArgumentException("tasa fuera de rango: " + tasa);
        }
        if (canales < 1 || canales > Downmix.MAX_CANALES_ENTRADA) {
            throw new IllegalArgumentException("canales fuera de rango: " + canales);
        }
        this.tasa = tasa;
        this.canales = canales;
        int largo = tasa / mcd(tasa, FRECUENCIA_HZ);
        double amplitud = 32767 * Math.pow(10, NIVEL_DBFS / 20);
        tabla = new short[largo];
        for (int i = 0; i < largo; i++) {
            tabla[i] = (short) Math.round(amplitud * StrictMath.sin(2 * Math.PI * FRECUENCIA_HZ * i / tasa));
        }
    }

    @Override
    public int tasa() {
        return tasa;
    }

    @Override
    public int canales() {
        return canales;
    }

    @Override
    public int leer(short[] destino, int desdeFrame, int frames) {
        int o = desdeFrame * canales;
        for (int f = 0; f < frames; f++) {
            short v = tabla[pos];
            for (int c = 0; c < canales; c++) {
                destino[o++] = v;
            }
            if (++pos == tabla.length) {
                pos = 0;
            }
        }
        return frames;
    }

    private static int mcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
