package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

/** Reproductor con una SalidaFalsa (semántica del AudioTrack de 2.3) y tiempo virtual. */
public class ReproductorTest {

    private static final Config ESTEREO = Config.imaAdpcm(48000, 2, 2000);
    private static final Config MONO = Config.imaAdpcm(44100, 1, 2000);
    /** Buffer de la salida: 8 bloques (≈ 171 ms a 48 kHz). */
    private static final int C = 8192;
    /** 70 % de 2000 ms a 48 kHz: ceil(1400 / 21,333) bloques. */
    private static final int UMBRAL_ESTEREO = 66;
    private static final long MS = 1000000L;

    private final ColaBloques cola = new ColaBloques(188, 1032);
    private SalidaFalsa salida = new SalidaFalsa(C, 256, 0, 0);
    private Reproductor r = new Reproductor(cola, salida, 4000);
    private long t;
    private long seq;
    private CodificadorIma codificador;
    private Config formato;

    @After
    public void sinErroresDeSalida() {
        assertNull(salida.error);
    }

    private void nuevo(SalidaFalsa s) {
        salida = s;
        r = new Reproductor(cola, salida, 4000);
    }

    /** Encola {@code n} bloques de una señal constante {@code valor} (o un seno si es null). */
    private void encolar(Config c, int n, int valor) {
        if (codificador == null || formato != c) {
            codificador = new CodificadorIma(c.canales, c.muestrasPorBloque);
            formato = c;
        }
        short[] pcm = new short[c.muestrasPorBloque * c.canales];
        java.util.Arrays.fill(pcm, (short) valor);
        for (int i = 0; i < n; i++) {
            ColaBloques.Bloque b = cola.tomarLibre();
            if (b == null) {
                fail("pool agotado");
            }
            codificador.codificarBloque(pcm, 0, c.muestrasPorBloque, b.datos, 0);
            b.largo = c.bytesBloque();
            cola.encolar(b, seq++);
        }
    }

    private void pasos() {
        for (int i = 0; i < 10000; i++) {
            if (r.paso(t) != 0) {
                return;
            }
        }
        fail("paso() nunca pide esperar");
    }

    private void correr(long ms) {
        for (long i = 0; i < ms; i++) {
            salida.avanzar(MS);
            t += MS;
            pasos();
        }
    }

    private void arrancarEstereo() {
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void sinConfigNoAbreNiEscribe() {
        correr(300);
        assertEquals(0, salida.aperturas);
        assertEquals(0, salida.llamadasEscribir);
        assertEquals(Reproductor.SIN_CONFIG, r.estado());
        assertEquals(0, r.bufferMs());
    }

    @Test
    public void llenaHastaElUmbralSinEscribir() {
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, UMBRAL_ESTEREO - 1, 1000);
        correr(500);
        assertEquals(1, salida.aperturas);
        assertEquals(2, salida.canales());
        assertEquals(48000, salida.tasa());
        assertEquals("en LLENANDO no se escribe", 0, salida.llamadasEscribir);
        assertEquals(Reproductor.LLENANDO, r.estado());
        assertFalse(r.reproduciendo());
        assertEquals(ESTEREO.msParaBloques(UMBRAL_ESTEREO - 1), r.bufferMs());

        encolar(ESTEREO, 1, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
        assertTrue(r.reproduciendo());
        assertTrue(salida.sonando());
        assertEquals("llena la salida de una vez", C, salida.pendiente());
        assertEquals(0, salida.llamadasEscribirSinSonar);
    }

    @Test
    public void elUmbralRespetaElBufferDeLaSalida() {
        // Objetivo de 100 ms: 70 % son 4 bloques, pero la salida necesita 8 + 2 para arrancar.
        Config corto = Config.imaAdpcm(48000, 2, 100);
        cola.nuevaGeneracion(corto);
        encolar(corto, 9, 1000);
        correr(100);
        assertEquals(Reproductor.LLENANDO, r.estado());
        encolar(corto, 1, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void elUmbralNoSuperaElPool() {
        Config largo = Config.imaAdpcm(48000, 2, 100000);
        cola.nuevaGeneracion(largo);
        encolar(largo, 185, 1000);
        correr(100);
        assertEquals(Reproductor.LLENANDO, r.estado());
        encolar(largo, 1, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void sinDatosCuentaUnCorteYRellenaConSilencio() {
        arrancarEstereo();
        correr(UMBRAL_ESTEREO * 22 + 200);
        assertEquals(1, r.cortes());
        assertEquals(Reproductor.RELLENANDO, r.estado());
        assertFalse(r.reproduciendo());
        assertEquals("el silencio mantiene la salida alimentada", 0, salida.faltas);
        assertTrue("sigue sonando (silencio)", salida.sonando());
        assertEquals(0, r.bufferMs());

        correr(2000);
        assertEquals("un corte largo es un solo corte", 1, r.cortes());

        encolar(ESTEREO, UMBRAL_ESTEREO - 1, 1000);
        correr(50);
        assertEquals(Reproductor.RELLENANDO, r.estado());
        encolar(ESTEREO, 1, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
        correr(UMBRAL_ESTEREO * 22 + 200);
        assertEquals(2, r.cortes());
        assertEquals(0, salida.faltas);
        assertEquals(0, r.subcortes());
    }

    @Test
    public void conDatosContinuosNoHayCortes() {
        arrancarEstereo();
        for (int i = 0; i < 3000; i++) { // 64 s
            encolar(ESTEREO, 1, 1000);
            correr(i % 3 == 2 ? 22 : 21); // 21,333 ms por bloque
        }
        assertEquals(0, r.cortes());
        assertEquals(0, r.subcortes());
        assertEquals(0, salida.faltas);
        assertTrue(r.minPendienteFrames() > 1024);
        long bufferMs = r.bufferMs();
        assertTrue("bufferMs " + bufferMs, bufferMs > 1300 && bufferMs < 1500);
    }

    @Test
    public void enPausaNoEscribe() {
        arrancarEstereo();
        correr(300);
        r.pedirPausa(true);
        pasos();
        assertFalse(salida.sonando());
        assertFalse(r.reproduciendo());
        assertTrue(r.pausado());
        int llamadas = salida.llamadasEscribir;
        long buffer = r.bufferMs();
        correr(3000);
        assertEquals("en pausa no se escribe", llamadas, salida.llamadasEscribir);
        assertEquals("en pausa el buffer no baja", buffer, r.bufferMs());
        assertEquals(0, r.cortes());

        r.pedirPausa(false);
        pasos();
        assertTrue(salida.sonando());
        assertTrue(r.reproduciendo());
        correr(300);
        assertEquals(0, r.cortes());
        assertEquals(0, salida.faltas);
    }

    @Test
    public void vaciarDescartaElAudioViejo() {
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, 100, 20000);
        correr(500);
        assertEquals(Reproductor.SONANDO, r.estado());

        cola.nuevaGeneracion(ESTEREO); // VACIAR
        salida.vigilar = true;
        salida.umbral = 10000;
        codificador.reiniciar(); // que el primer bloque nuevo no arranque desde +20000
        encolar(ESTEREO, 100, -20000);
        correr(1500);
        assertEquals("no suena nada de antes del VACIAR", 0, salida.sobreUmbral);
        assertEquals(1, salida.vaciados);
        assertEquals("mismo formato: no se recrea la salida", 1, salida.aperturas);
        assertEquals(Reproductor.SONANDO, r.estado());
        assertEquals("VACIAR no es corte", 0, r.cortes());
    }

    @Test
    public void vaciarEnPausaConservaLaPausa() {
        arrancarEstereo();
        correr(200);
        r.pedirPausa(true);
        pasos();
        cola.nuevaGeneracion(ESTEREO); // VACIAR
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(500);
        assertEquals(1, salida.vaciados);
        assertFalse(salida.sonando());
        assertFalse(r.reproduciendo());
        int llamadas = salida.llamadasEscribir;
        correr(500);
        assertEquals(llamadas, salida.llamadasEscribir);

        r.pedirPausa(false);
        correr(100);
        assertTrue(r.reproduciendo());
        assertEquals(0, r.cortes());
    }

    @Test
    public void reanudarSinDatosVuelveALlenarSinCorte() {
        arrancarEstereo();
        correr(200);
        r.pedirPausa(true);
        pasos();
        cola.nuevaGeneracion(ESTEREO); // VACIAR en pausa: no queda nada
        correr(100);
        r.pedirPausa(false);
        correr(1000);
        assertEquals(Reproductor.LLENANDO, r.estado());
        assertEquals(0, r.cortes());
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void detenerCierraLaSalida() {
        arrancarEstereo();
        correr(300);
        cola.nuevaGeneracion(null); // DETENER
        correr(100);
        assertEquals(Reproductor.SIN_CONFIG, r.estado());
        assertFalse(salida.abierta());
        assertEquals(0, r.cortes());
        assertEquals(0, r.bufferMs());
        assertNull(r.config());
    }

    @Test
    public void configMonoTrasEstereoRecreaLaSalida() {
        arrancarEstereo();
        correr(300);
        cola.nuevaGeneracion(MONO);
        encolar(MONO, 62, 1000); // 70 % de 2000 ms a 44,1 kHz = 61 bloques
        correr(100);
        assertEquals(2, salida.aperturas);
        assertEquals(1, salida.canales());
        assertEquals(44100, salida.tasa());
        assertEquals(Reproductor.SONANDO, r.estado());
        assertEquals(MONO, r.config());
        assertEquals(0, r.cortes());
    }

    @Test
    public void objetivoNuevoSinVaciar() {
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, 30, 1000);
        correr(100);
        assertEquals(Reproductor.LLENANDO, r.estado());
        cola.actualizarConfig(Config.imaAdpcm(48000, 2, 500)); // 70 % de 500 ms ≈ 17 bloques
        correr(10);
        assertEquals(Reproductor.SONANDO, r.estado());
        assertEquals(0, salida.vaciados);
    }

    /** Escrituras parciales: lo escrito es exactamente la secuencia decodificada. */
    @Test
    public void escriturasParcialesNoPierdenNiDuplicanMuestras() {
        nuevo(new SalidaFalsa(C, 256, 0, 40 * 1024));
        salida.maxPorEscritura = 300;
        Config c = Config.imaAdpcm(48000, 1, 300);
        CodificadorIma cod = new CodificadorIma(1, 1024);
        short[] senal = Senales.sintetica(40 * 1024, 1, 11);
        short[] esperado = new short[senal.length];
        byte[] bloque = new byte[cod.bytesBloque()];
        cola.nuevaGeneracion(c);
        for (int i = 0; i < 40; i++) {
            cod.codificarBloque(senal, i * 1024, 1024, bloque, 0);
            ImaAdpcm.decodificarBloque(bloque, 0, 1, 1024, esperado, i * 1024);
            ColaBloques.Bloque b = cola.tomarLibre();
            System.arraycopy(bloque, 0, b.datos, 0, bloque.length);
            b.largo = bloque.length;
            cola.encolar(b, i);
        }
        correr(1000);
        assertEquals(40 * 1024, salida.largoHistorial);
        for (int i = 0; i < esperado.length; i++) {
            if (salida.historial[i] != esperado[i]) {
                fail("muestra " + i + ": " + salida.historial[i] + " en vez de " + esperado[i]);
            }
        }
    }

    @Test
    public void salidaQueNoAceptaSeReabre() {
        arrancarEstereo();
        encolar(ESTEREO, 20, 1000);
        correr(300);
        salida.escrituraRota = true;
        correr(300);
        assertEquals(1, r.erroresSalida());
        assertEquals(2, salida.aperturas);
        salida.escrituraRota = false;
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(200);
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void abrirFallidoSeReintentaCadaSegundo() {
        salida.fallarAbrir = true;
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(2500);
        assertEquals(Reproductor.SIN_CONFIG, r.estado());
        assertEquals("primer intento + uno por segundo", 3, r.erroresSalida());
        salida.fallarAbrir = false;
        correr(1000);
        assertEquals(1, salida.aperturas);
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void reiniciarMetricasPoneCortesEnCero() {
        arrancarEstereo();
        correr(UMBRAL_ESTEREO * 22 + 200);
        assertEquals(1, r.cortes());
        r.reiniciarMetricas();
        pasos();
        assertEquals(0, r.cortes());
        assertEquals(0, r.decodPromedioUs());
    }

    /** La posición de la salida da la vuelta en 2³¹: bufferMs sigue siendo correcto. */
    @Test
    public void bufferMsConLaPosicionDandoLaVuelta() {
        nuevo(new SalidaFalsa(C, 256, Integer.MAX_VALUE - 3000, 0));
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        pasos();
        for (int i = 0; i < 300; i++) {
            encolar(ESTEREO, 1, 1000);
            correr(i % 3 == 2 ? 22 : 21);
            long esperado = ESTEREO.msParaBloques(cola.enCola()) + ESTEREO.msParaFrames(salida.pendiente());
            long b = r.bufferMs();
            assertTrue("bufferMs " + b + ", esperado ≈ " + esperado, Math.abs(b - esperado) <= 22);
        }
        assertEquals(0, r.cortes());
        assertTrue(salida.framesReproducidos() < 0); // dio la vuelta
    }

    @Test
    public void salidaTrabadaSeReabre() {
        arrancarEstereo();
        encolar(ESTEREO, 60, 1000);
        correr(500);
        salida.trabada = true;
        correr(1000);
        assertEquals("todavía no: 1,5 s de margen", 0, r.erroresSalida());
        correr(1000);
        assertEquals(1, r.erroresSalida());
        assertEquals(2, salida.aperturas);
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(300);
        assertEquals(Reproductor.SONANDO, r.estado());
        long antes = salida.framesSonados;
        correr(500);
        assertTrue("vuelve a sonar", salida.framesSonados > antes);
    }

    @Test
    public void salidaRecreadaPorDentroSeReabre() {
        nuevo(new SalidaFalsa(C, 256, 0, 0));
        arrancarEstereo();
        encolar(ESTEREO, 60, 1000);
        correr(1000);
        salida.recrearSola(); // posición de vuelta a 0 y sin sonar: sin reabrir, quedaría muda
        correr(100);
        assertEquals(1, r.erroresSalida());
        assertEquals(2, salida.aperturas);
        correr(300);
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void pausaLargaNoEsSalidaTrabada() {
        arrancarEstereo();
        correr(300);
        r.pedirPausa(true);
        pasos();
        correr(5000);
        r.pedirPausa(false);
        correr(300);
        assertEquals(0, r.erroresSalida());
        assertEquals(1, salida.aperturas);
    }

    /** Corte → PAUSA → REANUDAR sin datos → LLENANDO: lo que quedó en la salida es silencio. */
    @Test
    public void reanudarTrasCorteNoCuentaElSilencioComoAudio() {
        arrancarEstereo();
        correr(UMBRAL_ESTEREO * 22 + 200);
        assertEquals(Reproductor.RELLENANDO, r.estado());
        r.pedirPausa(true);
        pasos();
        r.pedirPausa(false);
        pasos();
        assertEquals("sin datos vuelve a llenar", Reproductor.LLENANDO, r.estado());
        assertEquals(1, r.cortes());
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
        // Suena parte del silencio que quedó en la salida y detrás entran los primeros bloques reales.
        correr(50);
        long enCola = ESTEREO.msParaBloques(cola.enCola());
        long real = r.bufferMs() - enCola;
        assertTrue("audio real pendiente " + real + " ms (sin el silencio de la salida)", real <= 3 * 22);
    }

    /**
     * Con la latencia real de un hilo (despierta cuando vence la espera o llega un bloque, más
     * 15 ms), el corte se declara con ~1 bloque real (21 ms) todavía en la salida: el silencio entra
     * a tiempo y AudioFlinger no queda sin datos.
     */
    @Test
    public void corteConLatenciaDelHiloSinFaltas() {
        nuevo(new SalidaFalsa(C, 256, 0, 0)); // el mezclador consume cada 5,3 ms
        arrancarEstereo();
        long despertar = 0;
        for (int i = 0; i < 4000; i++) {
            salida.avanzar(MS);
            t += MS;
            if (t >= despertar) {
                long espera = 0;
                for (int k = 0; k < 1000 && espera == 0; k++) {
                    espera = r.paso(t);
                }
                despertar = t + (espera + 15) * MS; // 15 ms de demora del planificador
            }
        }
        assertEquals(1, r.cortes());
        assertEquals("el silencio llegó antes de que la salida se vaciara", 0, salida.faltas);
    }

    @Test
    public void bufferMsCuentaLaColaAunqueLaSalidaNoAbra() {
        salida.fallarAbrir = true;
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, 10, 1000);
        correr(10);
        assertEquals(Reproductor.SIN_CONFIG, r.estado());
        assertEquals(ESTEREO.msParaBloques(10), r.bufferMs());
    }

    /**
     * En 2.3 una pista reanudada no suena hasta volver a llenarse. REANUDAR con la salida a medio
     * llenar y sin bloques para completarla vuelve a LLENANDO (antes quedaba "sonando" muda).
     */
    @Test
    public void reanudarSinConQueLlenarLaSalidaVuelveALlenar() {
        arrancarEstereo();
        correr(UMBRAL_ESTEREO * 22 - 120); // quedan ~5 bloques reales en la salida y la cola vacía
        assertEquals(0, cola.enCola());
        r.pedirPausa(true);
        pasos();
        r.pedirPausa(false);
        pasos();
        assertEquals(Reproductor.LLENANDO, r.estado());
        assertFalse(r.reproduciendo());
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(300);
        assertEquals(Reproductor.SONANDO, r.estado());
        long antes = salida.framesSonados;
        correr(300);
        assertTrue("vuelve a sonar", salida.framesSonados > antes);
        assertEquals(0, r.cortes());
    }

    /** Si reabrir no arregla la salida, tras 3 intentos seguidos espera 5 s antes del siguiente. */
    @Test
    public void salidaQueNoSeArreglaNoSeReabreSinParar() {
        arrancarEstereo();
        encolar(ESTEREO, 100, 1000);
        correr(300);
        salida.trabadaSiempre = true;
        for (int i = 0; i < 10; i++) {
            encolar(ESTEREO, Math.min(60, cola.capacidadBloques() - cola.enCola() - 2), 1000);
            correr(1000);
        }
        assertTrue("reaperturas en 10 s: " + r.erroresSalida(), r.erroresSalida() >= 3 && r.erroresSalida() <= 4);
    }

    /** FIN: suena todo lo que queda, sin corte, y queda en espera («fin de la cola»). */
    @Test
    public void finSuenaLoQueQuedaSinCorte() {
        arrancarEstereo();
        cola.marcarFin();
        correr(UMBRAL_ESTEREO * 22 + 500);
        assertEquals(0, r.cortes());
        assertEquals(0, salida.faltas);
        assertTrue(r.finalizado());
        assertFalse(r.reproduciendo());
        assertEquals(Reproductor.LLENANDO, r.estado());
        assertEquals(0, r.bufferMs());
        assertTrue("sonó todo: " + salida.framesSonados, salida.framesSonados >= UMBRAL_ESTEREO * 1024L);
    }

    /** Con menos audio que el umbral (pista corta), FIN lo hace sonar igual: rellena con silencio. */
    @Test
    public void finConPocoAudioSuenaIgual() {
        cola.nuevaGeneracion(ESTEREO);
        encolar(ESTEREO, 5, 1000);
        correr(300);
        assertEquals("sin FIN espera el umbral", Reproductor.LLENANDO, r.estado());
        assertEquals(0, salida.framesSonados);
        cola.marcarFin();
        correr(1000);
        assertTrue("sonaron los 5 bloques: " + salida.framesSonados, salida.framesSonados >= 5 * 1024L);
        assertTrue(r.finalizado());
        assertEquals(0, r.cortes());
    }

    /** Un AUDIO después del FIN (otra pista sin pausa) lo anula: sigue sonando, sin corte. */
    @Test
    public void audioTrasFinSigueSinCorte() {
        arrancarEstereo();
        cola.marcarFin();
        correr(500);
        encolar(ESTEREO, 60, 1000);
        correr(1500);
        assertEquals(Reproductor.SONANDO, r.estado());
        assertEquals(0, r.cortes());
        assertFalse(r.finalizado());
    }

    /** FIN durante un corte: suena lo que quedó en la cola y termina. */
    @Test
    public void finDuranteUnCorte() {
        arrancarEstereo();
        correr(UMBRAL_ESTEREO * 22 + 200);
        assertEquals(Reproductor.RELLENANDO, r.estado());
        encolar(ESTEREO, 3, 1000);
        cola.marcarFin();
        correr(1000);
        assertTrue(r.finalizado());
        assertEquals(1, r.cortes());
    }

    /** Tras terminar con FIN, audio nuevo vuelve a llenar y suena (la salida quedó vaciada). */
    @Test
    public void audioDespuesDeTerminarVuelveASonar() {
        arrancarEstereo();
        cola.marcarFin();
        correr(UMBRAL_ESTEREO * 22 + 500);
        assertTrue(r.finalizado());
        long antes = salida.framesSonados;
        encolar(ESTEREO, UMBRAL_ESTEREO, 1000);
        correr(500);
        assertEquals(Reproductor.SONANDO, r.estado());
        assertTrue(salida.framesSonados > antes);
        assertFalse(r.finalizado());
        assertEquals(0, r.cortes());
    }

    // --- En vivo: compensación de deriva.

    private static final Config VIVO = Config.imaAdpcm(48000, 2, 500, true);
    private static final Config NO_VIVO = Config.imaAdpcm(48000, 2, 500, false);
    private double alimentados;

    /** Corre {@code ms} encolando bloques a {@code factor} × el ritmo con que suena la salida. */
    private void correrAlimentando(Config c, long ms, double factor) {
        for (long i = 0; i < ms; i++) {
            alimentados += c.sampleRate / 1000.0 * factor;
            while (alimentados >= c.muestrasPorBloque) {
                alimentados -= c.muestrasPorBloque;
                encolar(c, 1, 1000);
            }
            correr(1);
        }
    }

    private void arrancarCon(Config c, int bloques) {
        cola.nuevaGeneracion(c);
        encolar(c, bloques, 1000);
        pasos();
        assertEquals(Reproductor.SONANDO, r.estado());
    }

    @Test
    public void margenesDeDerivaProporcionalesAlObjetivo() {
        // Normal (y el emisor Android): idénticos a los fijos de antes.
        assertEquals(150, Reproductor.bandaDeriva(500));
        assertEquals(100, Reproductor.vueltaDeriva(500));
        assertEquals(400, Reproductor.topeDeriva(500));
        // Baja.
        assertEquals(75, Reproductor.bandaDeriva(250));
        assertEquals(50, Reproductor.vueltaDeriva(250));
        assertEquals(200, Reproductor.topeDeriva(250));
        // Mínima.
        assertEquals(45, Reproductor.bandaDeriva(150));
        assertEquals(30, Reproductor.vueltaDeriva(150));
        assertEquals(120, Reproductor.topeDeriva(150));
        // Límites.
        assertEquals(40, Reproductor.bandaDeriva(50));
        assertEquals(120, Reproductor.topeDeriva(50));
        assertEquals(150, Reproductor.bandaDeriva(2000));
        assertEquals(400, Reproductor.topeDeriva(2000));
    }

    /** Con 250 ms, lo de más se corrige hasta ⅔ de la banda chica (no la de 500). */
    @Test
    public void enVivoBajaUsaSuBanda() {
        Config c = Config.imaAdpcm(48000, 2, 250, true);
        arrancarCon(c, 17); // ≈ 363 ms: 113 por encima, fuera de la banda de 75
        correrAlimentando(c, 120000, 1.0);
        assertTrue("descarta", r.framesDescartados() > 100);
        assertTrue("volvió a la banda: " + r.bufferMs(), r.bufferMs() < 250 + Reproductor.vueltaDeriva(250) + 30);
        assertEquals(0, r.cortes());
    }

    /**
     * Un objetivo menor que el piso de la salida (8192 frames + 2 bloques = 213 ms) no se puede
     * sostener: el receptor usa el piso, sin cortes ni bloques descartados.
     */
    @Test
    public void enVivoBajoElPisoDeLaSalidaUsaElPiso() {
        Config c = Config.imaAdpcm(48000, 2, 150, true);
        arrancarCon(c, 10);
        correrAlimentando(c, 60000, 1.0);
        assertEquals(0, r.cortes());
        assertEquals(0, r.bloquesDescartados());
        assertEquals(0, r.framesDuplicados());
        long piso = 213;
        assertTrue("cerca del piso: " + r.bufferMs(), Math.abs(r.bufferMs() - piso) <= Reproductor.bandaDeriva(piso));
    }

    @Test
    public void enVivoDentroDeLaBandaNoCorrige() {
        arrancarCon(VIVO, 24); // ≈ 512 ms
        correrAlimentando(VIVO, 20000, 1.0);
        assertEquals(0, r.framesDescartados());
        assertEquals(0, r.framesDuplicados());
        assertEquals(0, r.cortes());
    }

    @Test
    public void enVivoConDeMasDescartaHastaVolver() {
        arrancarCon(VIVO, 33); // ≈ 700 ms: 200 por encima
        correrAlimentando(VIVO, 5000, 1.0);
        assertTrue("descarta", r.framesDescartados() > 100);
        assertEquals(0, r.framesDuplicados());
        assertTrue(r.bufferMs() < 700);
        correrAlimentando(VIVO, 120000, 1.0);
        long d = r.framesDescartados();
        assertTrue("volvió a la banda: " + r.bufferMs(), r.bufferMs() < 500 + Reproductor.vueltaDeriva(500) + 30);
        correrAlimentando(VIVO, 20000, 1.0);
        assertEquals("y deja de corregir", d, r.framesDescartados());
        assertEquals(0, r.cortes());
        assertEquals(0, r.bloquesDescartados());
    }

    @Test
    public void enVivoConPocoDuplica() {
        arrancarCon(VIVO, 18); // ≈ 384 ms, cerca del borde de abajo
        correrAlimentando(VIVO, 120000, 0.9995); // el emisor va 0,05 % más lento: −0,5 ms por segundo
        assertTrue("duplica: " + r.framesDuplicados(), r.framesDuplicados() > 500);
        assertEquals(0, r.framesDescartados());
        assertEquals(0, r.cortes());
        assertTrue("dentro de la banda: " + r.bufferMs(), r.bufferMs() > 500 - Reproductor.bandaDeriva(500) - 40);
    }

    @Test
    public void sinEnVivoNoCorrige() {
        arrancarCon(NO_VIVO, 40);
        correrAlimentando(NO_VIVO, 20000, 1.0);
        assertEquals(0, r.framesDescartados());
        assertEquals(0, r.framesDuplicados());
        assertEquals(0, r.bloquesDescartados());
    }

    @Test
    public void enVivoMuyAtrasadoDescartaBloques() {
        arrancarCon(VIVO, 24);
        correrAlimentando(VIVO, 2000, 1.0);
        encolar(VIVO, 40, 1000); // llega de golpe lo que retuvo el enlace (~850 ms)
        correrAlimentando(VIVO, 1000, 1.0);
        assertTrue(r.bloquesDescartados() > 0);
        assertTrue("vuelve bajo el tope: " + r.bufferMs(), r.bufferMs() <= 500 + Reproductor.topeDeriva(500) + 30);
        assertEquals(0, r.cortes());
    }
}
