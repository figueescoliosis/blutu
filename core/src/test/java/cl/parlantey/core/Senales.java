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
