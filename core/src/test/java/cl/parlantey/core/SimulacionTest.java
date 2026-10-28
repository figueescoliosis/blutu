package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Emisor y receptor completos en un solo hilo, con tiempo virtual (pasos de 1 ms) y un enlace
 * simulado como el de la Fase 0: 165 KB/s y ~1,5 MB de buffer en el socket del emisor. Cada
 * "hilo" se modela como en las apps: el de red del receptor lee las tramas que llegaron, el de
 * STATE hace su tick, el de audio da pasos hasta que pide esperar (y se despierta antes si llega
 * un bloque), y el emisor da pasos de su flujo (despertando con cada STATE) y un PING por segundo.
 */
public class SimulacionTest {

    private static final long MS = 1000000L;
    private static final int KBPS_ENLACE = 165000;
    private static final int BUFFER_SOCKET = 1500000;
    private static final Hello HELLO = new Hello(Protocolo.VERSION, 10, "GT-S5360L", 2, 4000);

    /** Fuente estéreo de valor constante, que se puede cambiar (para ver qué suena después de VACIAR). */
    private static final class FuenteConstante implements Fuente {
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

    private static final class Simulacion implements FlujoEmisor.Estados, SesionReceptor.Oyente {
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
        FlujoEmisor flujo;
        /** Si no es null, el emisor es esta cola (sobre {@link #flujo}). */
        final ControlCola control;
        /** Títulos de los TRACK que recibió el receptor, en orden. */
        final List<String> tracks = new ArrayList<String>();
        /** Lo que había sonado de cada clase al llegar cada TRACK. */
        final List<long[]> sonadosAlTrack = new ArrayList<long[]>();

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

        Simulacion(FlujoEmisor flujo, int capacidadSalida, int granularidad) throws IOException {
            this(flujo, null, capacidadSalida, granularidad);
        }

        Simulacion(ControlCola control, int capacidadSalida, int granularidad) throws IOException {
            this(control.flujo(), control, capacidadSalida, granularidad);
        }

        private Simulacion(FlujoEmisor flujo, ControlCola control, int capacidadSalida, int granularidad)
                throws IOException {
            salida = new SalidaFalsa(capacidadSalida, granularidad, 0, 0);
            reproductor = new Reproductor(cola, salida, HELLO.capacidadBufferMs);
            this.flujo = flujo;
            this.control = control;
            iniciarConexion();
        }

        /** HELLO del receptor; el emisor lo lee y conecta el flujo. */
        private void iniciarConexion() throws IOException {
            receptor = new SesionReceptor(er.entrada, re.salida, HELLO, cola, reproductor, this, () -> ahora[0]);
            receptor.iniciar();
            re.avanzar(MS);
            if (!re.tramaCompleta() || lectorEmisor.leer() != Protocolo.HELLO) {
                fail("no llegó el HELLO");
            }
            if (control != null) {
                control.conectar(escritorEmisor, this);
            } else if (flujo != null) {
                flujo.conectar(escritorEmisor, this);
            }
        }

        /**
         * Se corta el enlace (lo que estaba en tránsito se pierde) y el emisor reconecta: enlaces y
         * sesión nuevos, pero la misma cola y el mismo reproductor, como en ServicioReceptor.
         */
        void reconectar() throws IOException {
            receptor.terminar();
            pasosAudio(ahora[0]);
            if (control != null) {
                control.desconectar();
            } else if (flujo != null) {
                flujo.desconectar();
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

        @Override
        public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
        }

        @Override
        public void track(Track track) {
            tracks.add(track.titulo);
            sonadosAlTrack.add(salida.sonadosPorClase.clone());
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
                if (flujo != null && (t >= despertarEmisorNs || estadosRecibidos != estadosVistos)) {
                    estadosVistos = estadosRecibidos;
                    long espera = pasosEmisor(t);
                    despertarEmisorNs = t + (espera == FlujoEmisor.FIN ? 50 : espera) * MS;
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
                long espera = control != null ? control.paso(t) : flujo.paso(t);
                nivelMaxMs = Math.max(nivelMaxMs, flujo.nivelEstimadoMs());
                if (espera != 0) {
                    return espera;
                }
            }
            throw new AssertionError("el flujo nunca pide esperar");
        }

        private void cerrarReceptor(long t) {
            receptorCerro = true;
            cierreReceptorNs = t;
            receptor.terminar();
            pasosAudio(t);
        }
    }

    private static final Track TONO = new Track("Tono de prueba", "440 Hz", 0);

    private static FlujoEmisor tono(Preset preset) {
        FlujoEmisor f = new FlujoEmisor(FlujoEmisor.OBJETIVO_ARCHIVOS_MS);
        f.reproducir(new CodificadorPista(new FuenteTono(48000, 2), preset), TONO, true);
        return f;
    }

    /** Criterio de la Fase 2 en la JVM: 10 minutos de tono sin cortes, con el nivel acotado. */
    @Test
    public void diezMinutosDeTonoSinCortes() throws IOException {
        for (Preset preset : new Preset[] {Preset.ALTA, Preset.SEGURA}) {
            Simulacion s = new Simulacion(tono(preset), 8192, 1024);
            s.correr(10 * 60 * 1000);
            Config c = s.flujo.config();
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
        FlujoEmisor p = new FlujoEmisor(2000);
        p.reproducir(new CodificadorPista(new FuenteConstante(20000), Preset.ALTA), null, true);
        Simulacion s = new Simulacion(p, 8192, 1024);
        s.correr(10000);
        assertEquals(Reproductor.SONANDO, s.reproductor.estado());

        p.reproducir(new CodificadorPista(new FuenteConstante(-20000), Preset.ALTA), null, true);
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
        s.flujo.detener();
        s.correr(1000);
        assertTrue("el flujo terminó", s.flujo.detenido());
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
        s.flujo.reproducir(new CodificadorPista(new FuenteTono(48000, 2), Preset.ALTA), TONO, true);
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

    // --- Cola de pistas (Fase 3).

    /** Valor constante de la pista k (clase k + 1 en la salida; 0 es silencio). */
    private static final int PASO_NIVEL = 3000;

    private static final SalidaFalsa.Clasificador POR_NIVEL = v -> v <= 0 ? 0
            : Math.min(SalidaFalsa.MAX_CLASES - 1, (v + PASO_NIVEL / 2) / PASO_NIVEL);

    /** Pista sintética de valor constante (fácil de reconocer en la salida pese al ADPCM). */
    private static final class PistaSintetica {
        final int tasa;
        final int canales;
        final long frames;
        long demoraMs = 30;
        boolean falla;

        PistaSintetica(int tasa, int canales, double segundos) {
            this.tasa = tasa;
            this.canales = canales;
            this.frames = Math.round(tasa * segundos);
        }

        PistaSintetica demora(long ms) {
            demoraMs = ms;
            return this;
        }

        PistaSintetica falla() {
            falla = true;
            return this;
        }
    }

    private static final class FuenteNivel implements Fuente {
        private final int tasa;
        private final int canales;
        private final short valor;
        private long quedan;

        FuenteNivel(int tasa, int canales, long frames, int valor) {
            this.tasa = tasa;
            this.canales = canales;
            this.quedan = frames;
            this.valor = (short) valor;
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
            if (quedan == 0) {
                return FIN;
            }
            int n = (int) Math.min(frames, quedan);
            java.util.Arrays.fill(destino, desdeFrame * canales, (desdeFrame + n) * canales, valor);
            quedan -= n;
            return n;
        }
    }

    /** Abre pistas sintéticas con una demora en tiempo virtual, como MediaCodec en otro hilo. */
    private static final class FabricaSintetica implements ControlCola.Fabrica {
        final PistaSintetica[] pistas;
        final long[] ahora;
        /** Pistas que la cola muestra (para agregar después). */
        int visibles;
        int abiertas;
        int cerradas;
        final List<Long> desdes = new ArrayList<Long>();

        FabricaSintetica(long[] ahora, PistaSintetica... pistas) {
            this.ahora = ahora;
            this.pistas = pistas;
            this.visibles = pistas.length;
        }

        @Override
        public int cantidad() {
            return visibles;
        }

        @Override
        public ControlCola.Apertura abrir(final int indice, final long desdeMs) {
            final PistaSintetica p = pistas[indice];
            final long listaNs = ahora[0] + p.demoraMs * MS;
            abiertas++;
            desdes.add(desdeMs);
            return new ControlCola.Apertura() {
                boolean cerrada;

                @Override
                public boolean lista() {
                    return ahora[0] >= listaNs;
                }

                @Override
                public Fuente fuente() {
                    long saltar = Math.min(p.frames, desdeMs * p.tasa / 1000);
                    return p.falla ? null
                            : new FuenteNivel(p.tasa, p.canales, p.frames - saltar, PASO_NIVEL * (indice + 1));
                }

                @Override
                public Track track() {
                    return new Track("Pista " + (indice + 1), "sintética", p.frames * 1000 / p.tasa);
                }

                @Override
                public String error() {
                    return p.falla ? "no se pudo decodificar" : null;
                }

                @Override
                public void cerrar() {
                    if (cerrada) {
                        throw new AssertionError("apertura cerrada dos veces");
                    }
                    cerrada = true;
                    cerradas++;
                }
            };
        }

        /** Frames de la pista k que sonaron. */
        long sonados(Simulacion s, int k) {
            return s.salida.sonadosPorClase[k + 1];
        }
    }

    private static final class Cola {
        final ControlCola control = new ControlCola(FlujoEmisor.OBJETIVO_ARCHIVOS_MS);
        final Simulacion s;
        final FabricaSintetica fabrica;

        Cola(Preset preset, PistaSintetica... pistas) throws IOException {
            control.preset(preset);
            s = new Simulacion(control, 8192, 1024);
            s.salida.clasificador = POR_NIVEL;
            fabrica = new FabricaSintetica(s.ahora, pistas);
            control.cola(fabrica);
        }

        long sonados(int k) {
            return fabrica.sonados(s, k);
        }

        /** Corre hasta que la cola terminó de sonar entera (o falla tras {@code maxMs}). */
        void hastaFinalizar(long maxMs) throws IOException {
            for (long i = 0; i < maxMs && !control.finalizada(); i++) {
                s.tick();
            }
            assertTrue("la cola no terminó", control.finalizada());
            s.correr(500);
        }

        /** ms hasta que se cumple la condición (o falla tras {@code maxMs}). */
        long msHasta(java.util.function.BooleanSupplier condicion, long maxMs) throws IOException {
            for (long i = 0; i < maxMs; i++) {
                if (condicion.getAsBoolean()) {
                    return i;
                }
                s.tick();
            }
            fail("no pasó en " + maxMs + " ms");
            return -1;
        }

        void sinCortesNiErrores() {
            assertEquals("cortes", 0, s.reproductor.cortes());
            assertEquals("subcortes", 0, s.reproductor.subcortes());
            assertEquals("faltas en la salida", 0, s.salida.faltas);
            assertEquals("desbordes", 0, s.cola.desbordes());
            assertEquals("errores de salida", 0, s.reproductor.erroresSalida());
            assertNull(s.salida.error);
        }
    }

    /** Lo sonado de una pista coincide con su largo (salvo la rampa del códec al empezar y al terminar). */
    private static void sonoEntera(Cola c, int k) {
        long esperado = c.fabrica.pistas[k].frames;
        long sonados = c.sonados(k);
        assertTrue("pista " + (k + 1) + ": sonaron " + sonados + " de " + esperado + " frames",
                Math.abs(sonados - esperado) <= 64);
    }

    /**
     * Criterio de la Fase 3 en la JVM: una cola con cambios de formato y una pista de 0,5 s suena
     * entera, en orden, sin cortes; los TRACK llegan uno por pista.
     */
    @Test
    public void colaConCambiosDeFormatoSuenaEntera() throws IOException {
        Cola c = new Cola(Preset.ALTA,
                new PistaSintetica(44100, 2, 3.0076), // mismo formato que la siguiente: sin pausa
                new PistaSintetica(44100, 2, 0.5),
                new PistaSintetica(48000, 2, 2.0016), // otro formato: FIN y CONFIG
                new PistaSintetica(44100, 1, 2.0001), // mono con Alta sigue mono
                new PistaSintetica(48000, 1, 1.0));
        c.control.reproducir(0, 0);
        c.hastaFinalizar(30000);
        for (int k = 0; k < 5; k++) {
            sonoEntera(c, k);
        }
        c.sinCortesNiErrores();
        assertEquals(java.util.Arrays.asList("Pista 1", "Pista 2", "Pista 3", "Pista 4", "Pista 5"), c.s.tracks);
        assertEquals(0, c.control.errores());
        assertEquals("tres formatos distintos: tres AudioTrack", 4, c.s.salida.aperturas);
        assertFalse(c.s.estado.reproduciendo);
        assertTrue("solo queda abierta la última", c.fabrica.abiertas - c.fabrica.cerradas <= 1);
        c.control.terminar(); // como la app al terminar la cola
        c.s.correr(100);
        assertEquals(c.fabrica.abiertas, c.fabrica.cerradas);
        assertTrue("el receptor sigue en «Fin de la cola»", c.s.reproductor.finalizado());
        c.control.reproducir(0, 0); // y se puede volver a empezar
        c.s.correr(3000);
        assertEquals(Reproductor.SONANDO, c.s.reproductor.estado());
        assertEquals(0, c.s.reproductor.cortes());
    }

    /**
     * Varias pistas cortas seguidas (caben varias en el buffer del receptor): cada TRACK sale cuando
     * a la anterior le queda menos de un bloque, ni antes ni mucho después.
     */
    @Test
    public void pistasCortasSeguidasLleganConSuTrackATiempo() throws IOException {
        PistaSintetica[] pistas = new PistaSintetica[9];
        for (int k = 0; k < 8; k++) {
            pistas[k] = new PistaSintetica(44100, 2, k % 2 == 0 ? 0.3 : 0.75).demora(0);
        }
        pistas[8] = new PistaSintetica(44100, 2, 2);
        Cola c = new Cola(Preset.ALTA, pistas);
        c.control.reproducir(0, 0);
        c.hastaFinalizar(20000);
        List<String> esperados = new ArrayList<String>();
        for (int k = 0; k < 9; k++) {
            sonoEntera(c, k);
            esperados.add("Pista " + (k + 1));
        }
        assertEquals(esperados, c.s.tracks);
        for (int k = 1; k < 9; k++) {
            long[] al = c.s.sonadosAlTrack.get(k);
            long faltaba = pistas[k - 1].frames - al[k];
            assertTrue("TRACK " + (k + 1) + " con " + faltaba + " frames de la anterior por sonar", faltaba <= 1024 + 64);
            assertTrue("TRACK " + (k + 1) + " con " + al[k + 1] + " frames suyos ya sonados", al[k + 1] <= 44100 * 4 / 10);
        }
        c.sinCortesNiErrores();
    }

    /** Criterio de la Fase 3: pausa, reanudar y siguiente se oyen en menos de 1 s. */
    @Test
    public void pausaYSiguienteRespondenEnMenosDeUnSegundo() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 30).demora(100),
                new PistaSintetica(44100, 2, 30).demora(100), new PistaSintetica(44100, 2, 30).demora(100));
        c.control.reproducir(0, 0);
        c.s.correr(10000);
        assertEquals(Reproductor.SONANDO, c.s.reproductor.estado());

        c.control.pausar();
        long pausa = c.msHasta(() -> !c.s.salida.sonando(), 1000);
        long sonadosEnPausa = c.s.salida.framesSonados;
        c.s.correr(3000);
        assertEquals("en pausa no suena nada", sonadosEnPausa, c.s.salida.framesSonados);

        c.control.reanudar();
        long reanudar = c.msHasta(() -> c.s.salida.framesSonados > sonadosEnPausa, 1000);
        c.s.correr(5000);

        c.control.siguiente();
        long siguiente = c.msHasta(() -> c.sonados(1) > 0, 1000);
        long sonadosA = c.sonados(0);
        c.s.correr(5000);
        assertEquals("tras siguiente no suena nada de la anterior", sonadosA, c.sonados(0));
        assertEquals(1, c.control.indiceSonando());

        // Siguiente en pausa: va a la siguiente y suena.
        c.control.pausar();
        c.s.correr(1000);
        c.control.siguiente();
        long enPausa = c.msHasta(() -> c.sonados(2) > 0, 1000);
        assertFalse(c.control.pausado());
        c.s.correr(3000);
        c.sinCortesNiErrores();
        System.out.printf("Latencias: pausa %d ms, reanudar %d ms, siguiente %d ms, siguiente en pausa %d ms%n",
                pausa, reanudar, siguiente, enPausa);
    }

    /** "Siguiente" con la pista que suena ya enviada entera (el emisor ya manda la que sigue). */
    @Test
    public void siguienteAlFinalDeUnaPistaVaALaQueSigueALaQueSuena() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 5), new PistaSintetica(44100, 2, 4),
                new PistaSintetica(44100, 2, 3));
        c.control.reproducir(0, 0);
        c.msHasta(() -> c.control.flujo().sonandoPrevia(), 10000);
        c.s.correr(500);
        assertTrue("el emisor ya envía la pista 2", c.control.flujo().sonandoPrevia());
        assertEquals("pero suena la 1", 0, c.control.indiceSonando());
        assertEquals("y nada de la 2 todavía", 0, c.sonados(1));
        c.control.siguiente();
        c.hastaFinalizar(20000);
        assertTrue("la 1 se cortó", c.sonados(0) < c.fabrica.pistas[0].frames - 44100);
        sonoEntera(c, 1); // la 2 desde el principio, una sola vez
        sonoEntera(c, 2);
        assertEquals(java.util.Arrays.asList("Pista 1", "Pista 2", "Pista 3"), c.s.tracks);
        c.sinCortesNiErrores();
    }

    /** Pausa mientras se espera que suene el final antes de cambiar de formato. */
    @Test
    public void pausaMientrasSuenaElFinalAntesDeOtroFormato() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 3), new PistaSintetica(48000, 2, 3));
        c.control.reproducir(0, 0);
        c.msHasta(() -> c.control.flujo().esperandoVacio(), 10000);
        c.control.pausar();
        c.s.correr(10000);
        assertEquals("en pausa no empieza la siguiente", 0, c.sonados(1));
        assertTrue(c.control.flujo().esperandoVacio());
        c.control.reanudar();
        c.hastaFinalizar(20000);
        sonoEntera(c, 0);
        sonoEntera(c, 1);
        c.sinCortesNiErrores();
    }

    /** Se corta la conexión mientras suena el final: retoma desde lo que se escuchó y sigue la cola. */
    @Test
    public void reconectarMientrasSuenaElFinal() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 3), new PistaSintetica(48000, 2, 3));
        c.control.reproducir(0, 0);
        c.msHasta(() -> c.control.flujo().esperandoVacio(), 10000);
        c.s.correr(300);
        c.s.reconectar();
        c.hastaFinalizar(20000);
        long a = c.sonados(0);
        long frames = c.fabrica.pistas[0].frames;
        assertTrue("pista 1: sonaron " + a + " de " + frames, a >= frames - 64 && a <= frames + 44100 / 2);
        sonoEntera(c, 1);
        assertEquals(java.util.Arrays.asList("Pista 1", "Pista 1", "Pista 2"), c.s.tracks);
    }

    /** Tras una reconexión a mitad de pista, sigue desde lo que se escuchó: ni repite ni salta mucho. */
    @Test
    public void reconectarRetomaDondeSeEscuchaba() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 20), new PistaSintetica(44100, 2, 2));
        c.control.reproducir(0, 0);
        c.s.correr(10000);
        c.s.reconectar();
        long desde = c.fabrica.desdes.get(c.fabrica.desdes.size() - 1);
        assertTrue("reabre en " + desde + " ms", desde > 8000 && desde < 10000);
        c.s.correr(5000);
        c.s.reconectar(); // la segunda cuenta desde donde se reabrió la primera
        desde = c.fabrica.desdes.get(c.fabrica.desdes.size() - 1);
        assertTrue("reabre otra vez en " + desde + " ms", desde > 12000 && desde < 15000);
        c.hastaFinalizar(30000);
        long a = c.sonados(0);
        long frames = c.fabrica.pistas[0].frames;
        assertTrue("pista 1: sonaron " + a + " de " + frames, a >= frames - 64 && a <= frames + 44100 / 2);
        sonoEntera(c, 1);
        c.sinCortesNiErrores();
    }

    /** Una pista que no abre se salta (también la primera) y se avisa. */
    @Test
    public void pistaQueNoAbreSeSalta() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 1).falla(), new PistaSintetica(44100, 2, 2),
                new PistaSintetica(44100, 2, 2).falla(), new PistaSintetica(44100, 2, 2));
        c.control.reproducir(0, 0);
        c.hastaFinalizar(20000);
        assertTrue("la 1 no suena (solo la rampa de la 2)", c.sonados(0) < 64);
        sonoEntera(c, 1);
        assertTrue(c.sonados(2) < 64);
        sonoEntera(c, 3);
        assertEquals(2, c.control.errores());
        assertTrue(c.control.ultimoError(), c.control.ultimoError().startsWith("Pista 3"));
        assertEquals(java.util.Arrays.asList("Pista 2", "Pista 4"), c.s.tracks);
        c.sinCortesNiErrores();
    }

    /** Cambiar el preset con la pista sonando la sigue desde donde iba, con el formato nuevo. */
    @Test
    public void cambiarPresetSigueDondeIba() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(48000, 2, 20));
        c.control.reproducir(0, 0);
        c.s.correr(8000);
        c.control.cambiarPreset(Preset.SEGURA);
        c.msHasta(() -> c.s.salida.aperturas == 2, 2000);
        long antes = c.s.salida.framesSonados;
        c.hastaFinalizar(30000);
        long despues = c.s.salida.framesSonados - antes;
        assertEquals(24000, c.s.salida.tasa());
        assertEquals(1, c.s.salida.canales());
        long ms = antes / 48 + despues / 24;
        assertTrue("sonaron " + ms + " ms de 20000", ms >= 20000 - 50 && ms <= 20000 + 500);
        assertEquals(0, c.s.reproductor.cortes());
    }

    /** Anterior: con más de 3 s sonados reinicia la pista; si no, vuelve a la anterior. */
    @Test
    public void anteriorReiniciaOVuelve() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 20), new PistaSintetica(44100, 2, 20),
                new PistaSintetica(44100, 2, 20));
        c.control.reproducir(1, 0);
        c.s.correr(6000);
        c.control.anterior();
        c.s.correr(2000);
        assertEquals("reinició la misma", 1, c.control.indiceSonando());
        c.control.anterior();
        c.s.correr(2000);
        assertEquals("volvió a la anterior", 0, c.control.indiceSonando());
        c.control.anterior();
        c.s.correr(2000);
        assertEquals("la primera se reinicia", 0, c.control.indiceSonando());
        assertEquals(java.util.Arrays.asList("Pista 2", "Pista 2", "Pista 1", "Pista 1"), c.s.tracks);
        // Dos "siguiente" seguidos (antes de que abra la primera) avanzan dos.
        c.control.siguiente();
        c.control.siguiente();
        c.s.correr(2000);
        assertEquals(2, c.control.indiceSonando());
        c.control.siguiente(); // la última: detiene
        c.s.correr(500);
        assertTrue(c.control.flujo().detenido());
        assertEquals(Reproductor.SIN_CONFIG, c.s.reproductor.estado());
        c.sinCortesNiErrores();
    }

    /** Una reconexión en pausa sigue en pausa; al reanudar suena desde donde iba. */
    @Test
    public void reconectarEnPausaSigueEnPausa() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 12));
        c.control.reproducir(0, 0);
        c.s.correr(5000);
        c.control.pausar();
        c.s.correr(1000);
        long sonados = c.s.salida.framesSonados;
        c.s.reconectar();
        c.s.correr(3000);
        assertTrue(c.control.pausado());
        assertEquals("tras reconectar en pausa no suena nada", sonados, c.s.salida.framesSonados);
        c.control.reanudar();
        c.hastaFinalizar(20000);
        long a = c.sonados(0);
        long frames = c.fabrica.pistas[0].frames;
        assertTrue("sonaron " + a + " de " + frames, a >= frames - 64 && a <= frames + 44100 / 2);
        c.sinCortesNiErrores();
    }

    /** Pistas agregadas al final, mientras suena la última o cuando ya terminó, siguen sonando. */
    @Test
    public void agregarPistasAlFinalDeLaCola() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 3), new PistaSintetica(44100, 2, 2),
                new PistaSintetica(44100, 2, 2));
        c.fabrica.visibles = 1;
        c.control.cola(c.fabrica);
        c.control.reproducir(0, 0);
        c.s.correr(1000);
        c.fabrica.visibles = 2;
        c.control.cola(c.fabrica);
        c.hastaFinalizar(20000);
        sonoEntera(c, 0);
        sonoEntera(c, 1);
        c.fabrica.visibles = 3;
        c.control.cola(c.fabrica);
        c.s.correr(500);
        assertFalse(c.control.finalizada());
        c.hastaFinalizar(20000);
        sonoEntera(c, 2);
        assertEquals(java.util.Arrays.asList("Pista 1", "Pista 2", "Pista 3"), c.s.tracks);
        assertEquals(0, c.s.reproductor.cortes());
    }

    /** Se corta mientras la canción se abre (la primera, o dos cortes seguidos): al volver, suena. */
    @Test
    public void reconectarMientrasSeAbre() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 4).demora(1500));
        c.control.reproducir(0, 0);
        c.s.correr(500);
        c.s.reconectar();
        c.s.correr(500);
        c.s.reconectar(); // otra vez, antes de que termine de abrir
        c.hastaFinalizar(20000);
        sonoEntera(c, 0);
        c.sinCortesNiErrores();
    }

    /** Volver a tocar una canción cuando la cola ya terminó: no queda "finalizada" mientras suena. */
    @Test
    public void reproducirTrasElFinalNoQuedaFinalizada() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(44100, 2, 2), new PistaSintetica(44100, 2, 10).demora(800));
        c.control.reproducir(0, 0);
        c.fabrica.visibles = 1;
        c.control.cola(c.fabrica);
        c.hastaFinalizar(20000);
        c.fabrica.visibles = 2;
        c.control.cola(c.fabrica);
        c.control.reproducir(1, 0);
        c.s.correr(400);
        assertFalse("abriendo no es finalizada", c.control.finalizada());
        c.s.correr(3000);
        assertFalse(c.control.finalizada());
        c.s.reconectar(); // a media canción: retoma
        c.hastaFinalizar(30000);
        long b = c.sonados(1);
        long frames = c.fabrica.pistas[1].frames;
        assertTrue("pista 2: sonaron " + b + " de " + frames, b >= frames - 64 && b <= frames + 44100 / 2);
    }

    /** Cambiar el preset mientras se abre un "siguiente" no vuelve a la anterior. */
    @Test
    public void presetMientrasSeAbreNoCancelaSiguiente() throws IOException {
        Cola c = new Cola(Preset.ALTA, new PistaSintetica(48000, 2, 10), new PistaSintetica(48000, 2, 3).demora(500));
        c.control.reproducir(0, 0);
        c.s.correr(3000);
        c.control.siguiente();
        c.s.correr(100);
        c.control.cambiarPreset(Preset.SEGURA);
        c.hastaFinalizar(20000);
        assertEquals(1, c.control.indiceSonando());
        assertEquals("con el preset nuevo", 24000, c.s.salida.tasa());
        assertTrue("la 2 sonó", c.sonados(1) > 24000 * 2);
        assertTrue("la 1 no volvió a sonar: " + c.sonados(0), c.sonados(0) < 48000 * 5);
    }

    // --- En vivo (Fase 4).

    /** Captura en vivo simulada: el "hilo de captura" llena una ColaCaptura de a 10 ms con su propio reloj. */
    private static final class Vivo {
        final ColaCaptura captura = new ColaCaptura(48000, 2, 250);
        final ControlCola control;
        final long objetivo;
        final Simulacion s;
        /** Velocidad del reloj de captura respecto del de la salida del receptor. */
        final double factor;
        private double acumulado;
        private final short[] trozo = new short[2 * 2000];
        long ms;
        // Métricas desde medirDesdeMs.
        long medirDesdeMs = Long.MAX_VALUE;
        long bufferMin = Long.MAX_VALUE;
        long bufferMax;
        long latenciaMin = Long.MAX_VALUE;
        long latenciaMax;

        Vivo(Preset preset, double factor, long objetivo) throws IOException {
            this(preset, factor, objetivo, 8192);
        }

        Vivo(Preset preset, double factor, long objetivo, int capacidadSalida) throws IOException {
            this.factor = factor;
            this.objetivo = objetivo;
            control = new ControlCola(objetivo);
            for (int i = 0; i < trozo.length; i++) {
                trozo[i] = (short) (8000 * Math.sin(i / 2 * 2 * Math.PI * 440 / 48000));
            }
            control.preset(preset);
            control.modo(true, objetivo);
            s = new Simulacion(control, capacidadSalida, 1024);
            control.cola(new ControlCola.Fabrica() {
                @Override
                public int cantidad() {
                    return 1;
                }

                @Override
                public ControlCola.Apertura abrir(int indice, long desdeMs) {
                    captura.vaciar(); // al (re)empezar, lo viejo ya no sirve
                    return new ControlCola.Apertura() {
                        @Override
                        public boolean lista() {
                            return true;
                        }

                        @Override
                        public Fuente fuente() {
                            return captura;
                        }

                        @Override
                        public Track track() {
                            return new Track("Todo el audio", "", 0);
                        }

                        @Override
                        public String error() {
                            return null;
                        }

                        @Override
                        public void cerrar() {
                        }
                    };
                }
            });
            control.reproducir(0, 0);
        }

        /**
         * Latencia exacta: lo capturado que todavía no sonó (en la captura, el codificador, el enlace
         * y el receptor), descontando lo descartado y corregido.
         */
        long latenciaMs() {
            Reproductor r = s.reproductor;
            long frames = captura.framesEscritos() - captura.framesDescartados()
                    - r.bloquesDescartados() * Protocolo.MUESTRAS_POR_BLOQUE - r.framesDescartados()
                    + r.framesDuplicados() - s.salida.framesSonados;
            return frames * 1000 / 48000;
        }

        void correr(long n) throws IOException {
            for (long i = 0; i < n; i++) {
                ms++;
                if (ms % 10 == 0) {
                    acumulado += 480 * factor;
                    int f = (int) acumulado;
                    acumulado -= f;
                    captura.escribir(trozo, 0, f);
                }
                s.tick();
                if (ms >= medirDesdeMs) {
                    long b = s.reproductor.bufferMs();
                    bufferMin = Math.min(bufferMin, b);
                    bufferMax = Math.max(bufferMax, b);
                    long l = latenciaMs();
                    latenciaMin = Math.min(latenciaMin, l);
                    latenciaMax = Math.max(latenciaMax, l);
                }
            }
        }
    }

    /**
     * Criterio de la Fase 4 en la JVM: 30 min de captura con el reloj del emisor 0,05 % más rápido
     * y más lento que el del receptor: sin cortes, con el buffer en la banda y la latencia estable.
     */
    @Test
    public void treintaMinutosEnVivoConRelojesDistintos() throws IOException {
        for (long objetivo : OBJETIVOS_EN_VIVO) {
            for (double factor : new double[] {1.0005, 0.9995}) {
                treintaMinutosEnVivo(objetivo, factor);
            }
        }
    }

    /** Las latencias del emisor de PC: Normal, Baja y Mínima. */
    private static final long[] OBJETIVOS_EN_VIVO = {500, 250, 150};

    /** El objetivo que sostiene el receptor: no baja del piso de la salida de 8192 frames (8 bloques + 2 = 213 ms). */
    private static long sostenido(long objetivo) {
        return Math.max(objetivo, 10 * 1024 * 1000L / 48000);
    }

    private static void treintaMinutosEnVivo(long objetivo, double factor) throws IOException {
        {
            Vivo v = new Vivo(Preset.ALTA, factor, objetivo);
            v.medirDesdeMs = 3 * 60 * 1000; // tras llegar a la banda
            v.correr(30 * 60 * 1000);
            String p = objetivo + " ms ×" + factor + ": ";
            Reproductor r = v.s.reproductor;
            assertEquals(p + "cortes", 0, r.cortes());
            assertEquals(p + "faltas", 0, v.s.salida.faltas);
            assertEquals(p + "descartado en la captura", 0, v.captura.msDescartados());
            assertEquals(p + "bloques descartados", 0, r.bloquesDescartados());
            long o = sostenido(objetivo);
            long banda = Reproductor.bandaDeriva(o);
            assertTrue(p + "buffer " + v.bufferMin + "–" + v.bufferMax, v.bufferMin >= o - banda - 60
                    && v.bufferMax <= o + banda + 60);
            assertTrue(p + "latencia " + v.latenciaMin + "–" + v.latenciaMax, v.latenciaMax - v.latenciaMin < 2 * banda + 50
                    && v.latenciaMax < o + banda + 250);
            long deriva = Math.round(48000 * Math.abs(factor - 1) * 30 * 60);
            long corregidos = factor > 1 ? r.framesDescartados() : r.framesDuplicados();
            long alReves = factor > 1 ? r.framesDuplicados() : r.framesDescartados();
            assertTrue(p + "corregidos " + corregidos + " de " + deriva, corregidos > deriva / 2 && corregidos < deriva * 3 / 2);
            assertTrue(p + "al revés " + alReves, alReves < deriva / 10);
            System.out.printf("En vivo %s 30 min: buffer %d–%d ms, latencia %d–%d ms, %d frames corregidos (deriva %d)%n",
                    p, v.bufferMin, v.bufferMax, v.latenciaMin, v.latenciaMax, corregidos, deriva);
        }
    }

    /**
     * Con la salida real del Galaxy Y (NOTAS ⑦: 4 × getMinBufferSize = 371 ms a 48 kHz hasta la
     * 0.4.1; 2 × = 185 ms desde la 0.4.2), un objetivo bajo el piso (salida + 2 bloques) se sostiene
     * en el piso, y el emisor no debe frenarse por debajo de él (en el PC, la captura descartaba y
     * la latencia subía).
     */
    @Test
    public void enVivoConLaSalidaDelGalaxyYNoDescartaEnLaCaptura() throws IOException {
        for (int salida : new int[] {17808, 8904}) {
            for (long objetivo : OBJETIVOS_EN_VIVO) {
                conLaSalidaDelGalaxyY(salida, objetivo);
            }
        }
    }

    private static void conLaSalidaDelGalaxyY(int salida, long objetivo) throws IOException {
        long piso = ((salida + 1023) / 1024 + 2) * 1024 * 1000L / 48000; // como Reproductor.calcularUmbral
        {
            Vivo v = new Vivo(Preset.ALTA, 1.0005, objetivo, salida);
            v.medirDesdeMs = 60 * 1000;
            v.correr(5 * 60 * 1000);
            Reproductor r = v.s.reproductor;
            String p = objetivo + " ms, salida " + salida + ": ";
            assertEquals(p + "cortes", 0, r.cortes());
            assertEquals(p + "descartado en la captura", 0, v.captura.msDescartados());
            long o = Math.max(objetivo, piso);
            assertTrue(p + "latencia " + v.latenciaMin + "–" + v.latenciaMax,
                    v.latenciaMax < o + Reproductor.bandaDeriva(o) + 100);
            System.out.printf("En vivo %d ms con la salida del Galaxy Y (%d ms): buffer %d–%d ms, latencia %d–%d ms%n",
                    objetivo, salida * 1000 / 48000, v.bufferMin, v.bufferMax, v.latenciaMin, v.latenciaMax);
        }
    }

    /** Si el enlace se corta 3 s, se descarta lo viejo: al volver, la latencia es la de antes. */
    @Test
    public void enVivoTrasUnCorteDelEnlaceLaLatenciaNoCrece() throws IOException {
        for (long objetivo : OBJETIVOS_EN_VIVO) {
            trasUnCorteDelEnlace(objetivo);
        }
    }

    private static void trasUnCorteDelEnlace(long objetivo) throws IOException {
        Vivo v = new Vivo(Preset.ALTA, 1.0, objetivo);
        v.correr(60000);
        long antes = v.s.reproductor.bufferMs();
        v.s.er.pausado = true;
        v.correr(3000);
        v.s.er.pausado = false;
        v.correr(3000);
        assertTrue("se descartó lo viejo en la captura", v.captura.msDescartados() > 1000);
        v.medirDesdeMs = v.ms;
        v.correr(30000);
        long o = sostenido(objetivo);
        long banda = Reproductor.bandaDeriva(o);
        assertTrue(objetivo + " ms: buffer " + v.bufferMin + "–" + v.bufferMax + " (antes " + antes + "): lo retenido se descartó",
                v.bufferMax <= o + banda + 30);
        long ahora = v.s.reproductor.bufferMs();
        assertTrue("en la banda: " + ahora + " (antes " + antes + ")", Math.abs(ahora - o) <= banda);
        assertTrue("el receptor descartó lo viejo", v.s.reproductor.bloquesDescartados() > 0);
        assertTrue(v.s.reproductor.cortes() <= 1);
        System.out.printf("En vivo %d ms tras 3 s de corte: buffer antes %d, después %d–%d ms, %d ms descartados en la captura, %d bloques en el receptor%n",
                objetivo, antes, v.bufferMin, v.bufferMax, v.captura.msDescartados(), v.s.reproductor.bloquesDescartados());
    }
}
