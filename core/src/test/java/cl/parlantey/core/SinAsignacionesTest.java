package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.Test;

/**
 * El camino de audio no debe asignar memoria (en Dalvik, el GC provoca cortes). Se mide en la
 * JVM de los tests con ThreadMXBean, tras calentar. Los tests de core corren con -Xint
 * (core/build.gradle.kts): el escape analysis del JIT de HotSpot podría eliminar asignaciones que
 * Dalvik sí haría, y el test las dejaría pasar.
 */
public class SinAsignacionesTest {

    private static final int REPETICIONES = 2000;

    /** Para que las asignaciones del control positivo no se puedan eliminar. */
    static volatile Object sumidero;

    private static void sinAsignar(String que, int repeticiones, Runnable tarea) {
        for (int i = 0; i < 3; i++) {
            tarea.run(); // calentamiento (carga de clases)
        }
        long bytes = Senales.asignado(tarea);
        assumeTrue("la JVM no permite medir asignaciones", bytes >= 0);
        // Margen para ruido de la propia medición; una asignación por repetición superaría 1 KB.
        System.out.printf("%s: %d bytes asignados en %d repeticiones%n", que, bytes, repeticiones);
        assertEquals(que + " asignó memoria", 0, bytes / 1024);
    }

    @Test
    public void laMedicionDetectaAsignaciones() {
        long bytes = Senales.asignado(() -> {
            for (int i = 0; i < 100; i++) {
                sumidero = new byte[64];
            }
        });
        assumeTrue("la JVM no permite medir asignaciones", bytes >= 0);
        assertTrue("100 arreglos de 64 bytes deben medirse: " + bytes, bytes >= 100 * 64);
    }

    @Test
    public void decodificarBloque() {
        CodificadorIma codificador = new CodificadorIma(2, 1024);
        byte[] bloque = new byte[codificador.bytesBloque()];
        codificador.codificarBloque(Senales.sintetica(1024, 2, 1), 0, 1024, bloque, 0);
        short[] pcm = new short[2048];
        sinAsignar("decodificarBloque", REPETICIONES, () -> {
            for (int i = 0; i < REPETICIONES; i++) {
                ImaAdpcm.decodificarBloque(bloque, 0, 2, 1024, pcm, 0);
            }
        });
    }

    @Test
    public void codificarBloque() {
        CodificadorIma codificador = new CodificadorIma(2, 1024);
        byte[] bloque = new byte[codificador.bytesBloque()];
        short[] pcm = Senales.sintetica(1024, 2, 2);
        sinAsignar("codificarBloque", REPETICIONES, () -> {
            for (int i = 0; i < REPETICIONES; i++) {
                codificador.codificarBloque(pcm, 0, 1024, bloque, 0);
            }
        });
    }

    @Test
    public void decimarYDownmix() {
        DecimadorX2 decimador = new DecimadorX2(2);
        short[] entrada = Senales.sintetica(1024, 2, 3);
        short[] salida = new short[1024];
        short[] mono = new short[512];
        sinAsignar("DecimadorX2 + Downmix (bloques de 1024 frames)", REPETICIONES / 10, () -> {
            for (int i = 0; i < REPETICIONES / 10; i++) {
                decimador.procesar(entrada, 0, 1024, salida, 0);
                Downmix.aMono(salida, 0, 512, 2, mono, 0);
            }
        });
    }

    @Test
    public void escribirAudioYState() {
        EscritorTramas escritor = new EscritorTramas(new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int desde, int n) {
            }
        });
        byte[] bloque = new byte[1032];
        sinAsignar("EscritorTramas.audio + state", REPETICIONES, () -> {
            try {
                for (int i = 0; i < REPETICIONES; i++) {
                    escritor.audio(i, bloque, 0, bloque.length);
                    escritor.state(1000, 0, i, true);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    public void leerAudio() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        EscritorTramas escritor = new EscritorTramas(bytes);
        byte[] bloque = new byte[1032];
        for (int i = 0; i < 4 * REPETICIONES + 100; i++) {
            escritor.audio(i, bloque, 0, bloque.length);
        }
        LectorTramas lector = new LectorTramas(new ByteArrayInputStream(bytes.toByteArray()));
        byte[] destino = new byte[1032];
        sinAsignar("LectorTramas + leer AUDIO", REPETICIONES / 2, () -> {
            try {
                for (int i = 0; i < REPETICIONES / 2; i++) {
                    lector.leer();
                    lector.datos().readInt();
                    lector.datos().readFully(destino);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }
}
