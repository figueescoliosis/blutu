package cl.parlantey.pc;

/**
 * Convierte el formato de mezcla de Windows (float de 32 bits o entero de 16, 24 o 32 bits, 1 a 8
 * canales intercalados, little-endian) a PCM16 de 1 o 2 canales: con más de 2, se toman los dos
 * primeros (frontal izquierdo y derecho). No asigna memoria.
 */
public final class Conversor {

    private final int canalesEntrada;
    private final int bytesMuestra;
    private final boolean flotante;
    private final int canalesSalida;

    /**
     * @param bits bits por muestra en el contenedor (16, 24 o 32)
     * @throws IllegalArgumentException si el formato no se puede convertir
     */
    public Conversor(int canales, int bits, boolean flotante) {
        if (canales < 1 || canales > 8) {
            throw new IllegalArgumentException("canales: " + canales);
        }
        if (flotante ? bits != 32 : bits != 16 && bits != 24 && bits != 32) {
            throw new IllegalArgumentException("formato no soportado: " + bits + " bits" + (flotante ? " float" : ""));
        }
        this.canalesEntrada = canales;
        this.bytesMuestra = bits / 8;
        this.flotante = flotante;
        this.canalesSalida = Math.min(2, canales);
    }

    public int canalesSalida() {
        return canalesSalida;
    }

    public int bytesPorFrame() {
        return bytesMuestra * canalesEntrada;
    }

    /** Convierte {@code frames} frames de {@code datos} a {@code destino} (desde 0). */
    public void convertir(byte[] datos, int frames, short[] destino) {
        int o = 0;
        int paso = bytesPorFrame();
        for (int f = 0; f < frames; f++) {
            int base = f * paso;
            for (int c = 0; c < canalesSalida; c++) {
                destino[o++] = muestra(datos, base + c * bytesMuestra);
            }
        }
    }

    private short muestra(byte[] d, int i) {
        if (flotante) {
            int bits = (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | d[i + 3] << 24;
            float v = Float.intBitsToFloat(bits);
            if (Float.isNaN(v)) {
                return 0;
            }
            int s = Math.round(v * 32767f);
            return (short) Math.max(-32768, Math.min(32767, s));
        }
        switch (bytesMuestra) {
            case 2:
                return (short) ((d[i] & 0xFF) | d[i + 1] << 8);
            case 3:
                return (short) ((d[i + 1] & 0xFF) | d[i + 2] << 8);
            default:
                return (short) ((d[i + 2] & 0xFF) | d[i + 3] << 8);
        }
    }
}
