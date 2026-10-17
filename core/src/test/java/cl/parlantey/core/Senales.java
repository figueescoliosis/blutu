package cl.parlantey.core;

import java.lang.reflect.Method;
import java.util.zip.CRC32;

/** Utilidades de los tests: señales, CRC y medición de asignaciones. */
final class Senales {

    private Senales() {
    }

    /**
     * Señal sintética solo con enteros (triángulo con barrido + ruido de un LCG), idéntica a la de
     * {@code herramientas/referencia_core.py}.
     */
    static short[] sintetica(int frames, int canales, long semilla) {
        short[] s = new short[frames * canales];
        long x = semilla;
        int fase = 0;
        int inc = 50;
        for (int i = 0; i < frames; i++) {
            fase = (fase + inc) & 0xFFFF;
            if (i % 64 == 63) {
                inc = inc % 3000 + 7;
            }
            int tri = fase < 32768 ? fase - 16384 : 49151 - fase;
            for (int c = 0; c < canales; c++) {
                x = (x * 1103515245L + 12345) & 0x7FFFFFFFL;
                int ruido = (int) (x >> 16) - 16384;
                s[i * canales + c] = saturar(tri * (c + 1) + (ruido >> 2));
            }
        }
        return s;
    }

    /** Onda cuadrada a escala completa, igual que en referencia_core.py: lleva el índice a 88. */
    static short[] cuadrada(int frames, int periodo) {
        short[] s = new short[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = (short) ((i / periodo) % 2 == 0 ? 32767 : -32768);
        }
        return s;
    }

    /** Ruido a escala completa de un LCG, igual que en referencia_core.py. */
    static short[] ruidoCompleto(int frames, int canales, long semilla) {
        short[] s = new short[frames * canales];
        long x = semilla;
        for (int i = 0; i < s.length; i++) {
            x = (x * 1103515245L + 12345) & 0x7FFFFFFFL;
            s[i] = (short) ((x >> 15) - 32768);
        }
        return s;
    }

    /** Seno de {@code frecuencia} Hz con amplitud relativa a escala completa (1.0 = 32767). */
    static short[] seno(int frames, int canales, int tasa, double frecuencia, double amplitud) {
        short[] s = new short[frames * canales];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < canales; c++) {
                // Cada canal con otra fase, para que no sean idénticos.
                double fase = 2 * Math.PI * frecuencia * i / tasa + c * Math.PI / 2;
                s[i * canales + c] = (short) Math.round(amplitud * 32767 * Math.sin(fase));
            }
        }
        return s;
    }

    /**
     * SNR en dB de un tono de {@code frecuencia} Hz en {@code x[desde, desde + n)} (canal 0 de
     * {@code canales}): ajusta por mínimos cuadrados a·sen + b·cos + c y compara la energía del
     * ajuste con la del residuo. No depende de la fase ni del retardo.
     */
    static double snrTono(short[] x, int desde, int n, int canales, int tasa, double frecuencia) {
        double[][] m = new double[3][3];
        double[] v = new double[3];
        for (int i = 0; i < n; i++) {
            double w = 2 * Math.PI * frecuencia * i / tasa;
            double[] base = {Math.sin(w), Math.cos(w), 1};
            double y = x[desde + i * canales];
            for (int a = 0; a < 3; a++) {
                v[a] += base[a] * y;
                for (int b = 0; b < 3; b++) {
                    m[a][b] += base[a] * base[b];
                }
            }
        }
        double[] c = resolver3(m, v);
        double senal = 0;
        double ruido = 0;
        for (int i = 0; i < n; i++) {
            double w = 2 * Math.PI * frecuencia * i / tasa;
            double ajuste = c[0] * Math.sin(w) + c[1] * Math.cos(w);
            double d = x[desde + i * canales] - ajuste - c[2];
            senal += ajuste * ajuste;
            ruido += d * d;
        }
        return 10 * Math.log10(senal / Math.max(ruido, 1e-9));
    }

    private static double[] resolver3(double[][] m, double[] v) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, a[i], 0, 3);
            a[i][3] = v[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int i = col + 1; i < 3; i++) {
                if (Math.abs(a[i][col]) > Math.abs(a[piv][col])) {
                    piv = i;
                }
            }
            double[] t = a[col];
            a[col] = a[piv];
            a[piv] = t;
            for (int i = 0; i < 3; i++) {
                if (i != col) {
                    double f = a[i][col] / a[col][col];
                    for (int j = col; j < 4; j++) {
                        a[i][j] -= f * a[col][j];
                    }
                }
            }
        }
        return new double[] {a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2]};
    }

    static short saturar(int v) {
        return (short) Math.max(-32768, Math.min(32767, v));
    }

    static long crc(byte[] datos) {
        CRC32 crc = new CRC32();
        crc.update(datos, 0, datos.length);
        return crc.getValue();
    }

    /** CRC32 de muestras como bytes big-endian. */
    static long crc(short[] pcm, int desde, int n) {
        byte[] b = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            b[2 * i] = (byte) (pcm[desde + i] >> 8);
            b[2 * i + 1] = (byte) pcm[desde + i];
        }
        return crc(b);
    }

    /** SNR en dB de {@code y} respecto de {@code x}, solo con las muestras del canal {@code c}. */
    static double snr(short[] x, short[] y, int frames, int canales, int c) {
        double senal = 0;
        double ruido = 0;
        for (int i = 0; i < frames; i++) {
            double a = x[i * canales + c];
            double d = a - y[i * canales + c];
            senal += a * a;
            ruido += d * d;
        }
        return 10 * Math.log10(senal / ruido);
    }

    /**
     * Bytes asignados por el hilo actual al ejecutar {@code tarea}, descontando lo que asigna la
     * propia medición. Usa com.sun.management.ThreadMXBean por reflexión (el classpath de compilación
     * es android.jar). Devuelve -1 si la JVM no lo soporta.
     */
    static long asignado(Runnable tarea) {
        try {
            Object bean = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Method bytes = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            Runnable vacia = () -> { };
            long base = medir(bean, bytes, vacia);
            for (int i = 0; i < 3; i++) {
                base = Math.min(base, medir(bean, bytes, vacia));
            }
            long total = medir(bean, bytes, tarea);
            for (int i = 0; i < 3; i++) {
                total = Math.min(total, medir(bean, bytes, tarea));
            }
            return Math.max(0, total - base);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static long medir(Object bean, Method bytes, Runnable tarea) throws ReflectiveOperationException {
        long antes = (Long) bytes.invoke(bean);
        tarea.run();
        long despues = (Long) bytes.invoke(bean);
        return despues - antes;
    }
}
