package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

/** SesionReceptor trama por trama, sin hilos: qué hace cada trama con la cola y el reproductor. */
public class SesionReceptorTest {

    private static final Hello HELLO = new Hello(Protocolo.VERSION, 10, "GT-S5360L", 2, 4000);
    private static final Config ESTEREO = Config.imaAdpcm(48000, 2, 2000);
    private static final Config MONO = Config.imaAdpcm(44100, 1, 2000);

    private final ByteArrayOutputStream entrada = new ByteArrayOutputStream();
    private final EscritorTramas emisor = new EscritorTramas(entrada);
    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private final ColaBloques cola = new ColaBloques(4, 1032);
    private final Reproductor reproductor = new Reproductor(cola, new SalidaFalsa(8192), 4000);
    private Track trackRecibido;
    private SesionReceptor sesion;

    /** Arma la sesión con todo lo que el "emisor" escribió hasta ahora y procesa esas tramas. */
    private void procesar(int tramas) throws IOException {
        if (sesion == null) {
            sesion = new SesionReceptor(new java.io.InputStream() {
                private int pos;

                @Override
                public int read() {
                    byte[] b = entrada.toByteArray();
                    return pos < b.length ? b[pos++] & 0xFF : -1;
                }

                @Override
                public int read(byte[] d, int desde, int n) {
                    byte[] b = entrada.toByteArray();
                    if (pos >= b.length) {
                        return -1;
                    }
                    int k = Math.min(n, b.length - pos);
                    System.arraycopy(b, pos, d, desde, k);
                    pos += k;
                    return k;
                }
            }, salida, HELLO, cola, reproductor, new SesionReceptor.Oyente() {
                @Override
                public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
                }

                @Override
                public void track(Track track) {
                    trackRecibido = track;
                }
            }, Reloj.SISTEMA);
            sesion.iniciar();
        }
        for (int i = 0; i < tramas; i++) {
            assertEquals(-1, sesion.procesarTrama());
        }
    }

    private void audio(Config c, long seq) throws IOException {
        emisor.audio(seq, new byte[c.bytesBloque()], 0, c.bytesBloque());
    }

    /** Tipos de las tramas que envió el receptor. */
    private java.util.List<Integer> respuestas() throws IOException {
        LectorTramas l = new LectorTramas(new ByteArrayInputStream(salida.toByteArray()));
        java.util.List<Integer> tipos = new java.util.ArrayList<Integer>();
        try {
            while (true) {
                tipos.add(l.leer());
            }
        } catch (java.io.EOFException fin) {
            return tipos;
        }
    }

    @Test
    public void audioSinConfigSeDescartaPeroCuentaParaUltimoSeq() throws IOException {
        audio(MONO, 5);
        procesar(1);
        assertEquals(5, cola.ultimoSeq());
        assertEquals(0, cola.enCola());

        emisor.config(ESTEREO);
        audio(ESTEREO, 6);
        procesar(2);
        assertEquals(6, cola.ultimoSeq());
        assertEquals(1, cola.enCola());
        assertEquals(ESTEREO, cola.config());

        emisor.control(Protocolo.CONTROL_DETENER);
        audio(ESTEREO, 7);
        procesar(2);
        assertNull("DETENER olvida el CONFIG", cola.config());
        assertEquals(7, cola.ultimoSeq());
        assertEquals(0, cola.enCola());
    }

    @Test
    public void poolAgotadoCuentaDesbordeYUltimoSeq() throws IOException {
        emisor.config(ESTEREO);
        for (int i = 0; i < 6; i++) {
            audio(ESTEREO, i);
        }
        procesar(7);
        assertEquals(4, cola.enCola());
        assertEquals(2, cola.desbordes());
        assertEquals(5, cola.ultimoSeq());
    }

    @Test
    public void configDelMismoFormatoNoVacia() throws IOException {
        emisor.config(ESTEREO);
        audio(ESTEREO, 0);
        emisor.config(Config.imaAdpcm(48000, 2, 500)); // otro objetivo, mismo formato
        procesar(3);
        assertEquals(1, cola.enCola());
        assertEquals(500, cola.config().bufferObjetivoMs);

        int gen = cola.generacion();
        emisor.config(MONO); // otro formato: generación nueva, cola vacía
        procesar(1);
        assertEquals(gen + 1, cola.generacion());
        assertEquals(0, cola.enCola());
    }

    @Test
    public void vaciarConservaElConfigYPausaVaAlReproductor() throws IOException {
        emisor.config(ESTEREO);
        audio(ESTEREO, 0);
        emisor.control(Protocolo.CONTROL_PAUSA);
        emisor.control(Protocolo.CONTROL_VACIAR);
        emisor.control(99); // comando desconocido: se ignora
        emisor.track(new Track("Tono de prueba", "440 Hz", 0));
        procesar(6);
        assertTrue(reproductor.pausado());
        assertEquals(0, cola.enCola());
        assertEquals(ESTEREO, cola.config());
        assertEquals("Tono de prueba", trackRecibido.titulo);
        emisor.control(Protocolo.CONTROL_REANUDAR);
        procesar(1);
        assertFalse(reproductor.pausado());
    }

    @Test
    public void pingRespondePongYStateLlegaDesdeElHello() throws IOException {
        emisor.ping(1234);
        procesar(1);
        // El primer STATE espera a que el hilo de audio ponga las métricas en cero (cortes de
        // la conexión anterior): sin un paso del reproductor, todavía no sale.
        assertTrue(sesion.tickEstado(System.nanoTime()));
        assertTrue(!respuestas().contains(Protocolo.STATE));
        reproductor.paso(System.nanoTime());
        assertTrue(sesion.tickEstado(System.nanoTime())); // ahora sale enseguida
        java.util.List<Integer> r = respuestas();
        assertEquals(Integer.valueOf(Protocolo.HELLO), r.get(0));
        assertTrue(r.contains(Protocolo.PONG));
        assertTrue(r.contains(Protocolo.STATE));
    }

    @Test
    public void tramasDeContenidoInvalidoTerminanConBye2() throws IOException {
        byte[][] invalidas = {
            {0x50, 0x59, 0x03, 0x00, 0, 0, 0, 2, 0, 1}, // AUDIO sin seq completo
            {0x50, 0x59, 0x02, 0x00, 0, 0, 0, 12, 1, 0, 0, (byte) 0xBB, (byte) 0x80, 2, 0x10, 0, 0, 0, 7, (byte) 0xD0},
        };
        // El segundo: CONFIG con 4096 muestras por bloque, que no cabe en los bloques del pool.
        for (byte[] t : invalidas) {
            entrada.reset();
            salida.reset();
            sesion = null;
            entrada.write(t);
            try {
                procesar(1);
                fail("debió rechazar la trama");
            } catch (ProtocoloException esperada) {
                java.util.List<Integer> r = respuestas();
                assertEquals(Integer.valueOf(Protocolo.BYE), r.get(r.size() - 1));
            }
        }
    }

    @Test
    public void terminarEsComoDetener() throws IOException {
        emisor.config(ESTEREO);
        audio(ESTEREO, 0);
        procesar(2);
        sesion.terminar();
        assertNull(cola.config());
        assertEquals(0, cola.enCola());
    }

    /** Mono con 2050 muestras: cabe en 1032 bytes, pero no en el bloque decodificado (2048). */
    @Test
    public void configQueNoCabeAlDecodificarSeRechaza() throws IOException {
        entrada.write(new byte[] {0x50, 0x59, 0x02, 0x00, 0, 0, 0, 12, 1, 0, 0, (byte) 0xAC, 0x44, 1, 0x08, 0x02,
            0, 0, 7, (byte) 0xD0});
        try {
            procesar(1);
            fail("debió rechazar el CONFIG");
        } catch (ProtocoloException esperada) {
            assertTrue(esperada.getMessage().contains("2050"));
        }
        assertNull(cola.config());
    }

    /**
     * La pausa, ultimoSeq y los cortes son de la conexión: la siguiente (misma cola y mismo
     * reproductor, como en el servicio) empieza sin pausa y con ultimoSeq = SEQ_NINGUNO.
     */
    @Test
    public void unaConexionNuevaNoHeredaPausaNiSeq() throws IOException {
        emisor.config(ESTEREO);
        audio(ESTEREO, 41);
        emisor.control(Protocolo.CONTROL_PAUSA);
        procesar(3);
        assertTrue(reproductor.pausado());
        assertEquals(41, cola.ultimoSeq());
        sesion.terminar();
        assertFalse("terminar quita la pausa", reproductor.pausado());

        reproductor.pedirPausa(true); // aunque quedara puesta por cualquier motivo
        entrada.reset();
        sesion = null;
        procesar(0); // conexión nueva: iniciar()
        assertFalse(reproductor.pausado());
        assertEquals(Protocolo.SEQ_NINGUNO, cola.ultimoSeq());
    }

    @Test
    public void detenerQuitaLaPausa() throws IOException {
        emisor.config(ESTEREO);
        emisor.control(Protocolo.CONTROL_PAUSA);
        emisor.control(Protocolo.CONTROL_DETENER);
        procesar(3);
        assertFalse(reproductor.pausado());
    }
}
