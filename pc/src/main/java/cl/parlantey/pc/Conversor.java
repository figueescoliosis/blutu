package cl.parlantey.pc;

/**
 * Convierte el formato de mezcla de Windows (float de 32 bits o entero de 16, 24 o 32 bits, 1 a 8
 * canales intercalados, little-endian) a PCM16 de 1 o 2 canales. Con más de 2 canales mezcla a
 * estéreo según la máscara de canales (WAVEFORMATEXTENSIBLE): el centro (donde va la voz en juegos y
 * películas) a los dos lados con −3 dB, los traseros y laterales con −6 dB, sin el LFE. No asigna memoria.
 */
public final class Conversor {

    // Bits de dwChannelMask (ksmedia.h).
    private static final int FL = 0x1;
    private static final int FR = 0x2;
    private static final int FC = 0x4;
    private static final int LFE = 0x8;
    private static final int BL = 0x10;
    private static final int BR = 0x20;
    private static final int FLC = 0x40;
    private static final int FRC = 0x80;
    private static final int BC = 0x100;
    private static final int SL = 0x200;
    private static final int SR = 0x400;
    /** Orden habitual cuando la máscara viene en 0: FL FR FC LFE BL BR SL SR. */
    private static final int[] ORDEN_POR_DEFECTO = {FL, FR, FC, LFE, BL, BR, SL, SR};

    private final int canalesEntrada;
    private final int bytesMuestra;
    private final boolean flotante;
    private final int canalesSalida;
    /** Coeficientes de cada canal de entrada hacia la izquierda y la derecha (solo con más de 2). */
    private final float[] aIzquierda;
    private final float[] aDerecha;

    public Conversor(int canales, int bits, boolean flotante) {
        this(canales, bits, flotante, 0);
    }

    /**
     * @param bits    bits por muestra en el contenedor (16, 24 o 32)
     * @param mascara dwChannelMask (0 si no hay)
     * @throws IllegalArgumentException si el formato no se puede convertir
     */
    public Conversor(int canales, int bits, boolean flotante, int mascara) {
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
        aIzquierda = new float[canales];
        aDerecha = new float[canales];
        if (canales > 2) {
            int[] posiciones = posiciones(canales, mascara);
            for (int c = 0; c < canales; c++) {
                switch (posiciones[c]) {
                    case FL:
                    case FLC:
                        aIzquierda[c] = 1f;
                        break;
                    case FR:
                    case FRC:
                        aDerecha[c] = 1f;
                        break;
                    case FC:
                    case BC:
                        aIzquierda[c] = 0.7071f;
                        aDerecha[c] = 0.7071f;
                        break;
                    case BL:
                    case SL:
                        aIzquierda[c] = 0.5f;
                        break;
                    case BR:
                    case SR:
                        aDerecha[c] = 0.5f;
                        break;
                    default:
                        break; // LFE y otros: fuera
                }
            }
        }
    }

    /** Posición (bit de la máscara) de cada canal: los bits encendidos, en orden. */
    private static int[] posiciones(int canales, int mascara) {
        int[] p = new int[canales];
        int c = 0;
        if (mascara != 0) {
            for (int bit = 1; bit != 0 && c < canales; bit <<= 1) {
                if ((mascara & bit) != 0) {
                    p[c++] = bit;
                }
            }
        }
        for (; c < canales; c++) {
            p[c] = c < ORDEN_POR_DEFECTO.length ? ORDEN_POR_DEFECTO[c] : 0;
        }
        return p;
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
        if (canalesEntrada <= 2) {
            for (int f = 0; f < frames; f++) {
                int base = f * paso;
                for (int c = 0; c < canalesSalida; c++) {
                    destino[o++] = saturar(muestra(datos, base + c * bytesMuestra));
                }
            }
            return;
        }
        for (int f = 0; f < frames; f++) {
            int base = f * paso;
            float l = 0;
            float r = 0;
            for (int c = 0; c < canalesEntrada; c++) {
                float v = muestra(datos, base + c * bytesMuestra);
                l += aIzquierda[c] * v;
                r += aDerecha[c] * v;
            }
            destino[o++] = saturar(l);
            destino[o++] = saturar(r);
        }
    }

    private static short saturar(float v) {
        int s = Math.round(v);
        return (short) Math.max(-32768, Math.min(32767, s));
    }

    /** La muestra en escala de int16 (sin saturar). */
    private float muestra(byte[] d, int i) {
        if (flotante) {
            int bits = (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | d[i + 3] << 24;
            float v = Float.intBitsToFloat(bits);
            return Float.isNaN(v) ? 0 : v * 32767f;
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
