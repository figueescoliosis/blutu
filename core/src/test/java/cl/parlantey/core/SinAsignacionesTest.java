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
 * JVM de los tests con ThreadMXBean, tras calentar. Los tests de core corren sin escape analysis
 * (core/build.gradle.kts): el JIT de HotSpot podría eliminar asignaciones que Dalvik sí haría, y
 * el test las dejaría pasar. {@link #laMedicionDetectaAsignacionesQueNoEscapan} lo comprueba.
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

    /** Un objeto que no sale del método: con escape analysis, el JIT lo eliminaría. */
    private static final class Par {
        final int a;
        final int b;

        Par(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    private static long sumarPares(int n) {
        long suma = 0;
        for (int i = 0; i < n; i++) {
            Par p = new Par(i, i + 1);
            suma += p.a + p.b;
        }
        return suma;
    }

    @Test
    public void laMedicionDetectaAsignacionesQueNoEscapan() {
        long[] suma = new long[1];
        for (int i = 0; i < 200; i++) {
            suma[0] += sumarPares(10000); // que el JIT compile sumarPares
        }
        long bytes = Senales.asignado(() -> suma[0] += sumarPares(10000));
        assumeTrue("la JVM no permite medir asignaciones", bytes >= 0);
        assertTrue("10000 objetos locales deben medirse (¿escape analysis activo?): " + bytes, bytes >= 10000 * 16);
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

    /** InputStream que repite un arreglo para siempre, sin asignar. */
    private static final class Repetir extends java.io.InputStream {
        private final byte[] datos;
        private int pos;

        Repetir(byte[] datos) {
            this.datos = datos;
        }

        @Override
        public int read() {
            int b = datos[pos] & 0xFF;
            pos = (pos + 1) % datos.length;
            return b;
        }

        @Override
        public int read(byte[] b, int desde, int n) {
            int k = Math.min(n, datos.length - pos);
            System.arraycopy(datos, pos, b, desde, k);
            pos = (pos + k) % datos.length;
            return k;
        }
    }

    private static final OutputStream NULO = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int desde, int n) {
        }
    };

    /**
     * Ciclo completo del receptor con las APIs de paso: tramas AUDIO (con PING y VACIAR
     * intercalados) → cola → generación → decodificar → escribir en la salida → tick de STATE.
     */
    @Test
    public void cicloDelReceptor() throws IOException {
        Config config = Config.imaAdpcm(48000, 2, 2000);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        EscritorTramas e = new EscritorTramas(bytes);
        e.config(config);
        int inicio = bytes.size();
        CodificadorIma cod = new CodificadorIma(2, 1024);
        byte[] bloque = new byte[cod.bytesBloque()];
        short[] senal = Senales.sintetica(1024, 2, 4);
        for (int i = 0; i < 500; i++) {
            cod.codificarBloque(senal, 0, 1024, bloque, 0);
            e.audio(i, bloque, 0, bloque.length);
            if (i % 50 == 0) {
                e.ping(i);
            }
            if (i == 250) {
                e.control(Protocolo.CONTROL_VACIAR);
            }
        }
        byte[] ciclo = java.util.Arrays.copyOfRange(bytes.toByteArray(), inicio, bytes.size());

        ColaBloques cola = new ColaBloques(188, 1032);
        SalidaFalsa salida = new SalidaFalsa(8192, 1024, 0, 0);
        Reproductor reproductor = new Reproductor(cola, salida, 4000);
        java.io.SequenceInputStream entrada = new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray(), 0, inicio), new Repetir(ciclo));
        SesionReceptor sesion = new SesionReceptor(entrada, NULO, new Hello(1, 10, "x", 2, 4000), cola,
                reproductor, null, Reloj.SISTEMA);
        sesion.iniciar();
        long[] t = {System.nanoTime()};
        Runnable vuelta = () -> {
            try {
                for (int i = 0; i < 600; i++) {
                    if (sesion.procesarTrama() >= 0) {
                        throw new AssertionError("BYE inesperado");
                    }
                    t[0] += 21333333L;
                    salida.avanzar(21333333L);
                    for (int k = 0; k < 4 && reproductor.paso(t[0]) == 0; k++) {
                        // otro paso
                    }
                    sesion.tickEstado(t[0]);
                    synchronized (cola) {
                        reproductor.bufferMs();
                    }
                }
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
        };
        for (int i = 0; i < 3; i++) {
            vuelta.run(); // llegar a SONANDO y cargar clases
        }
        long escritos = salida.framesEscritos;
        sinAsignar("ciclo del receptor (red + audio + STATE)", 600, vuelta);
        assertTrue("sonó durante la medición", salida.framesEscritos - escritos > 600 * 1024);
        assertTrue("pasó por VACIAR", salida.vaciados > 0);
        assertEquals(null, salida.error);
    }

    /** Pipeline del emisor con dos etapas de decimación y downmix (96 kHz estéreo → Segura). */
    @Test
    public void pipelineDelEmisor() {
        // Objetivo enorme: el control de flujo nunca frena y cada paso arma y envía un bloque.
        PipelineEmisor p = new PipelineEmisor(new FuenteTono(96000, 2), Preset.SEGURA, null, 0xFFFFFFFFL);
        p.conectar(new EscritorTramas(NULO), () -> null, 4000);
        sinAsignar("PipelineEmisor.paso (96 kHz estéreo → 24 kHz mono)", 300, () -> {
            try {
                for (int i = 0; i < 300; i++) {
                    if (p.paso(0) != 0) {
                        throw new AssertionError("el pipeline pidió esperar");
                    }
                }
            } catch (IOException ex) {
                throw new AssertionError(ex);
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
