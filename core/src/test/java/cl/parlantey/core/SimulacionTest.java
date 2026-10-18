package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

/**
 * Emisor y receptor completos en un solo hilo, con tiempo virtual (pasos de 1 ms) y un enlace
 * simulado como el de la Fase 0: 165 KB/s y ~1,5 MB de buffer en el socket del emisor. Cada
 * "hilo" se modela como en las apps: el de red del receptor lee las tramas que llegaron, el de
 * STATE hace su tick, el de audio da pasos hasta que pide esperar (y se despierta antes si llega
 * un bloque), y el emisor da pasos de su pipeline (despertando con cada STATE) y un PING por segundo.
 */
public class SimulacionTest {

    private static final long MS = 1000000L;
    private static final int KBPS_ENLACE = 165000;
    private static final int BUFFER_SOCKET = 1500000;
    private static final Hello HELLO = new Hello(Protocolo.VERSION, 10, "GT-S5360L", 2, 4000);

    /** Fuente estéreo de valor constante, que se puede cambiar (para ver qué suena después de VACIAR). */
    private static final class FuenteConstante implements PipelineEmisor.Fuente {
        volatile short valor;

        FuenteConstante(int valor) {
            this.valor = (short) valor;
        }

        @Override
        public int tasa() {
            return 48000;
        }

        @Override
        public int canales() {
            return 2;
        }

        @Override
        public int leer(short[] destino, int desdeFrame, int frames) {
            java.util.Arrays.fill(destino, desdeFrame * 2, (desdeFrame + frames) * 2, valor);
            return frames;
        }
    }

    private static final class Simulacion implements PipelineEmisor.Estados {
        final long[] ahora = {0};
        EnlaceSimulado er = new EnlaceSimulado(KBPS_ENLACE, BUFFER_SOCKET);
        EnlaceSimulado re = new EnlaceSimulado(KBPS_ENLACE, BUFFER_SOCKET);
        final ColaBloques cola = new ColaBloques(188, 1032);
        final SalidaFalsa salida;
        final Reproductor reproductor;
        SesionReceptor receptor;
        LectorTramas lectorEmisor = new LectorTramas(re.entrada);
        EscritorTramas escritorEmisor = new EscritorTramas(er.salida);
        /** Primer STATE recibido en la conexión actual. */
        Estado primerEstado;
        PipelineEmisor pipeline;

        Estado estado;
        long estadosRecibidos;
        boolean emisorMudo;
        boolean receptorCerro;
        long cierreReceptorNs = -1;
        long ultimaTramaReceptorNs;
        int byeRecibido = -1;
        private long despertarAudioNs;
        private long ultimoSeqVisto = Protocolo.SEQ_NINGUNO;
        private int generacionVista;
        private long despertarEmisorNs;
        private long estadosVistos;
        private long proximoPingNs;

        // Métricas.
        long nivelMaxMs;
        long bufferMinMs = Long.MAX_VALUE;
        long bufferMaxMs;
        int enColaMax;

        Simulacion(PipelineEmisor pipeline, int capacidadSalida, int granularidad) throws IOException {
            salida = new SalidaFalsa(capacidadSalida, granularidad, 0, 0);
            reproductor = new Reproductor(cola, salida, HELLO.capacidadBufferMs);
            this.pipeline = pipeline;
            iniciarConexion();
        }

        /** HELLO del receptor; el emisor lo lee y conecta el pipeline. */
        private void iniciarConexion() throws IOException {
            receptor = new SesionReceptor(er.entrada, re.salida, HELLO, cola, reproductor, null, () -> ahora[0]);
            receptor.iniciar();
            re.avanzar(MS);
            if (!re.tramaCompleta() || lectorEmisor.leer() != Protocolo.HELLO) {
                fail("no llegó el HELLO");
            }
            if (pipeline != null) {
                pipeline.conectar(escritorEmisor, this);
            }
        }

        /**
         * Se corta el enlace (lo que estaba en tránsito se pierde) y el emisor reconecta: enlaces y
         * sesión nuevos, pero la misma cola y el mismo reproductor, como en ServicioReceptor.
         */
        void reconectar() throws IOException {
            receptor.terminar();
            pasosAudio(ahora[0]);
            if (pipeline != null) {
                pipeline.desconectar();
            }
            er = new EnlaceSimulado(KBPS_ENLACE, BUFFER_SOCKET);
            re = new EnlaceSimulado(KBPS_ENLACE, BUFFER_SOCKET);
            lectorEmisor = new LectorTramas(re.entrada);
            escritorEmisor = new EscritorTramas(er.salida);
            estado = null;
            primerEstado = null;
            receptorCerro = false;
            iniciarConexion();
        }

        @Override
        public Estado ultimo() {
            return estado;
        }

        void correr(long ms) throws IOException {
            for (long i = 0; i < ms; i++) {
                tick();
            }
        }

        void tick() throws IOException {
            ahora[0] += MS;
            long t = ahora[0];
            er.avanzar(MS);
            re.avanzar(MS);
            salida.avanzar(MS);

            if (!receptorCerro) {
                // Hilo de red del receptor.
                while (er.tramaCompleta()) {
                    ultimaTramaReceptorNs = t;
                    int bye = receptor.procesarTrama();
                    if (bye >= 0) {
                        cerrarReceptor(t);
                        break;
                    }
                }
            }
            if (!receptorCerro) {
                // Hilo de STATE (watchdog incluido).
                if (!receptor.tickEstado(t)) {
                    cerrarReceptor(t);
                }
            }
            // Hilo de audio: despierta por tiempo, por un bloque nuevo o por una generación nueva.
            if (t >= despertarAudioNs || cola.ultimoSeq() != ultimoSeqVisto || cola.generacion() != generacionVista) {
                ultimoSeqVisto = cola.ultimoSeq();
                generacionVista = cola.generacion();
                despertarAudioNs = t + pasosAudio(t) * MS;
            }
            synchronized (cola) {
                long b = reproductor.bufferMs();
                if (reproductor.estado() == Reproductor.SONANDO) {
                    bufferMinMs = Math.min(bufferMinMs, b);
                }
                bufferMaxMs = Math.max(bufferMaxMs, b);
                enColaMax = Math.max(enColaMax, cola.enCola());
            }

            // Lector del emisor.
            while (re.tramaCompleta()) {
                int tipo = lectorEmisor.leer();
                if (tipo == Protocolo.STATE) {
                    estado = Estado.leer(lectorEmisor.datos());
                    if (primerEstado == null) {
                        primerEstado = estado;
                    }
                    estadosRecibidos++;
                } else if (tipo == Protocolo.BYE) {
                    byeRecibido = lectorEmisor.datos().readUnsignedByte();
                }
            }
            // Hilo de E/S del emisor.
            if (!emisorMudo) {
                if (t >= proximoPingNs) {
                    escritorEmisor.ping(t);
                    proximoPingNs = t + SesionEmisor.INTERVALO_PING_NS;
                }
                if (pipeline != null && (t >= despertarEmisorNs || estadosRecibidos != estadosVistos)) {
                    estadosVistos = estadosRecibidos;
                    long espera = pasosEmisor(t);
                    if (espera == PipelineEmisor.FIN) {
                        pipeline = null;
                    } else {
                        despertarEmisorNs = t + espera * MS;
                    }
                }
            }
        }

        private long pasosAudio(long t) {
            for (int i = 0; i < 10000; i++) {
                long espera = reproductor.paso(t);
                if (espera != 0) {
                    return espera;
                }
            }
            throw new AssertionError("el reproductor nunca pide esperar");
        }

        private long pasosEmisor(long t) throws IOException {
            for (int i = 0; i < 10000; i++) {
                long espera = pipeline.paso(t);
                nivelMaxMs = Math.max(nivelMaxMs, pipeline.nivelEstimadoMs());
                if (espera != 0) {
                    return espera;
                }
            }
            throw new AssertionError("el pipeline nunca pide esperar");
        }

        private void cerrarReceptor(long t) {
            receptorCerro = true;
            cierreReceptorNs = t;
            receptor.terminar();
            pasosAudio(t);
        }
    }

    private static PipelineEmisor tono(Preset preset) {
        return new PipelineEmisor(new FuenteTono(48000, 2), preset, new Track("Tono de prueba", "440 Hz", 0),
                PipelineEmisor.OBJETIVO_ARCHIVOS_MS);
    }

    /** Criterio de la Fase 2 en la JVM: 10 minutos de tono sin cortes, con el nivel acotado. */
    @Test
    public void diezMinutosDeTonoSinCortes() throws IOException {
        for (Preset preset : new Preset[] {Preset.ALTA, Preset.SEGURA}) {
            Simulacion s = new Simulacion(tono(preset), 8192, 1024);
            s.correr(10 * 60 * 1000);
            Config c = s.pipeline.config();
            String p = preset.nombre + ": ";
            assertEquals(p + "cortes", 0, s.reproductor.cortes());
            assertEquals(p + "subcortes", 0, s.reproductor.subcortes());
            assertEquals(p + "faltas en la salida", 0, s.salida.faltas);
            assertEquals(p + "desbordes", 0, s.cola.desbordes());
            assertEquals(p + "errores de salida", 0, s.reproductor.erroresSalida());
            assertNull(s.salida.error);
            assertTrue(p + "nivel máximo " + s.nivelMaxMs, s.nivelMaxMs <= 2000 + c.msParaBloques(1));
            assertTrue(p + "buffer mínimo " + s.bufferMinMs, s.bufferMinMs >= 1400);
            assertTrue(p + "buffer máximo " + s.bufferMaxMs, s.bufferMaxMs <= 2100);
            assertTrue(p + "cola máxima " + s.enColaMax, s.enColaMax < s.cola.capacidadBloques());
            assertTrue(p + "en tránsito " + s.er.maxEnTransitoVisto, s.er.maxEnTransitoVisto < 100000);
            // STATE cada 250 ms: 2400 en 10 minutos.
            assertTrue(p + "STATE recibidos " + s.estadosRecibidos, Math.abs(s.estadosRecibidos - 2400) <= 2);
            assertTrue(s.estado.reproduciendo);
            // Sonó todo menos el arranque (≈ 1,4 s de llenado + la salida).
            long sonados = s.salida.framesSonados;
            assertTrue(p + "frames sonados " + sonados, sonados >= (long) c.sampleRate * (600 - 2));
            System.out.printf("%s 10 min: nivel máx %d ms, buffer %d–%d ms, cola máx %d, en tránsito máx %d B,"
                    + " decodificación %d µs/bloque%n", preset.nombre, s.nivelMaxMs, s.bufferMinMs, s.bufferMaxMs,
                    s.enColaMax, s.er.maxEnTransitoVisto, s.reproductor.decodPromedioUs());
        }
    }

    /** Con una salida grande (512 ms) también arranca y suena sin cortes. */
    @Test
    public void salidaGrandeSinCortes() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.NORMAL), 24576, 1024);
        s.correr(60 * 1000);
        assertEquals(0, s.reproductor.cortes());
        assertEquals(0, s.salida.faltas);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());
    }

    @Test
    public void enlaceDetenido1sNoCorta() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.ALTA), 8192, 1024);
        s.correr(20000);
        s.er.pausado = true;
        s.correr(1000);
        s.er.pausado = false;
        s.correr(20000);
        assertEquals(0, s.reproductor.cortes());
        assertEquals(0, s.cola.desbordes());
    }

    @Test
    public void enlaceDetenido3sEsUnSoloCorteYSeRecupera() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.ALTA), 8192, 1024);
        s.correr(20000);
        s.er.pausado = true;
        s.correr(3000);
        assertEquals(1, s.reproductor.cortes());
        s.er.pausado = false;
        s.correr(5000);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());
        s.correr(30000);
        assertEquals("un solo corte", 1, s.reproductor.cortes());
        assertEquals(1, s.estado.cortes); // también en el STATE
        assertEquals(0, s.cola.desbordes());
        assertEquals(0, s.salida.faltas);
        assertTrue("nivel máximo " + s.nivelMaxMs, s.nivelMaxMs <= 2000 + 22);
        assertTrue(s.reproductor.bufferMs() >= 1400);
    }

    @Test
    public void vaciarDescartaLoViejoSinCorte() throws IOException {
        FuenteConstante fuente = new FuenteConstante(20000);
        PipelineEmisor p = new PipelineEmisor(fuente, Preset.ALTA, null, 2000);
        Simulacion s = new Simulacion(p, 8192, 1024);
        s.correr(10000);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());

        fuente.valor = -20000;
        p.pedirVaciar();
        s.salida.umbral = 10000;
        s.salida.vigilarAlVaciar = true;
        s.correr(10000);
        assertTrue(s.salida.vigilar);
        assertEquals(1, s.salida.vaciados);
        assertEquals("tras VACIAR no suena nada de antes", 0, s.salida.sobreUmbral);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());
        assertEquals("VACIAR no es corte", 0, s.reproductor.cortes());
        assertEquals("mismo formato: la salida no se recrea", 1, s.salida.aperturas);
        assertTrue(s.nivelMaxMs <= 2000 + 22);
        assertTrue(s.reproductor.bufferMs() >= 1400);
    }

    @Test
    public void detenerCierraLaSalidaYLaConexionSigue() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.NORMAL), 8192, 1024);
        s.correr(10000);
        s.pipeline.pedirDetener();
        s.correr(1000);
        assertNull("el pipeline terminó", s.pipeline);
        assertEquals(Reproductor.SIN_CONFIG, s.reproductor.estado());
        assertFalse(s.salida.abierta());
        assertEquals(0, s.reproductor.cortes());
        s.correr(10000);
        assertFalse("los PING mantienen viva la conexión", s.receptorCerro);
        assertFalse(s.estado.reproduciendo);
        assertEquals(0, s.estado.bufferMs);

        // Un tono nuevo en la misma conexión vuelve a sonar; el seq sigue (no vuelve a 0).
        long seqAntes = s.cola.ultimoSeq();
        assertTrue(seqAntes > 400);
        s.pipeline = tono(Preset.ALTA);
        s.pipeline.conectar(s.escritorEmisor, s);
        s.correr(5000);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());
        assertEquals(2, s.salida.canales());
        assertTrue(s.cola.ultimoSeq() > seqAntes + 200);
        assertEquals(0, s.reproductor.cortes());
    }

    @Test
    public void emisorMudoCierraTras5s() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.ALTA), 8192, 1024);
        s.correr(5000);
        s.emisorMudo = true;
        long mudoDesde;
        s.correr(4500);
        assertFalse("4,5 s de silencio todavía no", s.receptorCerro);
        s.emisorMudo = false;
        s.correr(2000);
        assertFalse(s.receptorCerro);

        s.emisorMudo = true;
        mudoDesde = s.ahora[0];
        s.correr(6000);
        assertTrue("el watchdog cerró", s.receptorCerro);
        long tras = (s.cierreReceptorNs - s.ultimaTramaReceptorNs) / MS;
        assertTrue("cerró " + tras + " ms después de la última trama", tras > 5000 && tras <= 5001);
        assertTrue(s.ultimaTramaReceptorNs - mudoDesde < 300 * MS);
        assertEquals("cerrar detiene la reproducción", Reproductor.SIN_CONFIG, s.reproductor.estado());
    }

    @Test
    public void audioDeLargoIncorrectoTerminaConBye2() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.ALTA), 8192, 1024);
        s.correr(3000);
        s.escritorEmisor.audio(999999, new byte[520], 0, 520); // estéreo exige 1032
        try {
            s.correr(100);
            fail("debió rechazar el AUDIO");
        } catch (ProtocoloException esperada) {
            assertTrue(esperada.getMessage().contains("AUDIO"));
        }
        s.receptorCerro = true;
        s.correr(100);
        assertEquals(Protocolo.BYE_PROTOCOLO, s.byeRecibido);
    }

    /**
     * Reconexión con la misma cola y el mismo reproductor (como en el servicio): una PAUSA de la
     * conexión anterior no deja mudo al receptor, el primer STATE no trae ultimoSeq ni cortes de
     * antes y una reconexión rápida no llena el buffer de más.
     */
    @Test
    public void reconectarNoArrastraNadaDeLaConexionAnterior() throws IOException {
        Simulacion s = new Simulacion(tono(Preset.ALTA), 8192, 1024);
        s.correr(1000); // conexión corta: ~94 bloques enviados, ultimoSeq alto
        s.er.pausado = true; // un corte de 3 s antes: 1 corte en esta conexión
        s.correr(3000);
        s.er.pausado = false;
        s.correr(2000);
        assertEquals(1, s.reproductor.cortes());
        s.escritorEmisor.control(Protocolo.CONTROL_PAUSA);
        s.correr(200);
        assertTrue(s.reproductor.pausado());

        s.reconectar();
        s.bufferMaxMs = 0;
        s.correr(6000);
        assertEquals("el primer STATE es de esta conexión", Protocolo.SEQ_NINGUNO, s.primerEstado.ultimoSeq);
        assertEquals("y sus cortes también", 0, s.primerEstado.cortes);
        assertEquals("la pausa no pasa a la conexión nueva", Reproductor.SONANDO, s.reproductor.estado());
        assertTrue(s.estado.reproduciendo);
        assertTrue("buffer máximo " + s.bufferMaxMs + " ms", s.bufferMaxMs <= 2100);
        assertEquals(0, s.reproductor.cortes());

        // Y otra reconexión enseguida (enlace inestable): tampoco se llena de más.
        s.correr(500);
        s.reconectar();
        s.bufferMaxMs = 0;
        s.correr(6000);
        assertTrue("buffer máximo " + s.bufferMaxMs + " ms", s.bufferMaxMs <= 2100);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());
    }
}
