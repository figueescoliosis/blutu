package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

/** Tramas de la Fase 1: CONFIG, AUDIO, TRACK, CONTROL y STATE (criterios ① y ②). */
public class MensajesTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final EscritorTramas escritor = new EscritorTramas(bytes);

    private LectorTramas lector() {
        return new LectorTramas(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static LectorTramas lector(byte[] b) {
        return new LectorTramas(new ByteArrayInputStream(b));
    }

    /** Una trama con cabecera válida y el payload dado. */
    private static byte[] trama(int tipo, int... payload) {
        byte[] b = new byte[Protocolo.LARGO_CABECERA + payload.length];
        b[0] = 0x50;
        b[1] = 0x59;
        b[2] = (byte) tipo;
        b[7] = (byte) payload.length;
        for (int i = 0; i < payload.length; i++) {
            b[Protocolo.LARGO_CABECERA + i] = (byte) payload[i];
        }
        return b;
    }

    // --- Byte a byte, contra las tablas de PROTOCOLO.md.

    @Test
    public void configEnBytes() throws IOException {
        escritor.config(Config.imaAdpcm(44100, 2, 500));
        assertArrayEquals(trama(Protocolo.CONFIG,
                0x01, // códec
                0x00, 0x00, 0xAC, 0x44, // 44100
                0x02, // canales
                0x04, 0x00, // 1024 muestras por bloque
                0x00, 0x00, 0x01, 0xF4), // 500 ms
                bytes.toByteArray());
    }

    @Test
    public void audioEnBytes() throws IOException {
        escritor.audio(0x01020304L, new byte[] {9, (byte) 0xAA, (byte) 0xBB, 9}, 1, 2);
        assertArrayEquals(trama(Protocolo.AUDIO, 0x01, 0x02, 0x03, 0x04, 0xAA, 0xBB), bytes.toByteArray());
    }

    @Test
    public void trackEnBytes() throws IOException {
        escritor.track(new Track("Té", "A", 1000));
        assertArrayEquals(trama(Protocolo.TRACK,
                0x00, 0x03, 'T', 0xC3, 0xA9, // "Té" en UTF-8 modificado
                0x00, 0x01, 'A',
                0x00, 0x00, 0x03, 0xE8), // 1000 ms
                bytes.toByteArray());
    }

    @Test
    public void controlEnBytes() throws IOException {
        escritor.control(Protocolo.CONTROL_VACIAR);
        assertArrayEquals(trama(Protocolo.CONTROL, 0x03), bytes.toByteArray());
    }

    @Test
    public void stateEnBytes() throws IOException {
        escritor.state(250, 2, Protocolo.SEQ_NINGUNO, true);
        assertArrayEquals(trama(Protocolo.STATE,
                0x00, 0x00, 0x00, 0xFA, // bufferMs
                0x00, 0x00, 0x00, 0x02, // cortes
                0xFF, 0xFF, 0xFF, 0xFF, // ultimoSeq: ninguno
                0x01), // reproduciendo
                bytes.toByteArray());
    }

    // --- Ida y vuelta con valores extremos.

    @Test
    public void configIdaYVuelta() throws IOException {
        Config[] configs = {
                Config.imaAdpcm(Protocolo.TASA_MIN, 1, 0),
                Config.imaAdpcm(Protocolo.TASA_MAX, 2, 0xFFFFFFFFL),
                new Config(Protocolo.CODEC_IMA_ADPCM, 22050, 1, 65534, 500), // el bloque mono más grande
        };
        for (Config c : configs) {
            escritor.config(c);
        }
        LectorTramas l = lector();
        for (Config c : configs) {
            assertEquals(Protocolo.CONFIG, l.leer());
            assertEquals(12, l.largo());
            assertEquals(c, Config.leer(l.datos()));
            assertEquals(0, l.datos().available());
        }
    }

    @Test
    public void audioIdaYVueltaConBloquesReales() throws IOException {
        for (int canales = 1; canales <= 2; canales++) {
            bytes.reset();
            Config config = Config.imaAdpcm(48000, canales, 500);
            CodificadorIma codificador = new CodificadorIma(canales, config.muestrasPorBloque);
            byte[] bloque = new byte[config.bytesBloque()];
            codificador.codificarBloque(Senales.sintetica(1024, canales, canales), 0, 1024, bloque, 0);

            long[] seqs = {0, 1, 0x7FFFFFFFL, 0xFFFFFFFFL};
            for (long seq : seqs) {
                escritor.audio(seq, bloque, 0, bloque.length);
            }
            LectorTramas l = lector();
            byte[] recibido = new byte[config.bytesBloque()];
            for (long seq : seqs) {
                assertEquals(Protocolo.AUDIO, l.leer());
                assertEquals("largo de AUDIO", config.largoAudio(), l.largo());
                assertEquals(seq, l.datos().readInt() & 0xFFFFFFFFL);
                l.datos().readFully(recibido);
                assertArrayEquals(bloque, recibido);
            }
        }
        assertEquals(520, Config.imaAdpcm(48000, 1, 0).largoAudio());
        assertEquals(1036, Config.imaAdpcm(48000, 2, 0).largoAudio());
    }

    @Test
    public void trackIdaYVuelta() throws IOException {
        Track[] tracks = {new Track("Canción de Ñandú ♪ 😀", "Los Jaivas", 0xFFFFFFFFL), new Track("", "", 0)};
        for (Track t : tracks) {
            escritor.track(t);
        }
        LectorTramas l = lector();
        for (Track t : tracks) {
            assertEquals(Protocolo.TRACK, l.leer());
            Track leido = Track.leer(l.datos());
            assertEquals(t.titulo, leido.titulo);
            assertEquals(t.artista, leido.artista);
            assertEquals(t.duracionMs, leido.duracionMs);
        }
    }

    @Test
    public void controlIdaYVuelta() throws IOException {
        int[] comandos = {Protocolo.CONTROL_PAUSA, Protocolo.CONTROL_REANUDAR, Protocolo.CONTROL_VACIAR,
                Protocolo.CONTROL_DETENER, 0xFF};
        for (int c : comandos) {
            escritor.control(c);
        }
        LectorTramas l = lector();
        for (int c : comandos) {
            assertEquals(Protocolo.CONTROL, l.leer());
            assertEquals(1, l.largo());
            assertEquals(c, l.datos().readUnsignedByte());
        }
    }

    @Test
    public void stateIdaYVuelta() throws IOException {
        escritor.state(0xFFFFFFFFL, 0, 0x80000000L, false);
        escritor.state(1234, 0xFFFFFFFFL, 0, true);
        LectorTramas l = lector();
        assertEquals(Protocolo.STATE, l.leer());
        Estado e = Estado.leer(l.datos());
        assertEquals(0xFFFFFFFFL, e.bufferMs);
        assertEquals(0, e.cortes);
        assertEquals(0x80000000L, e.ultimoSeq);
        assertFalse(e.reproduciendo);
        assertEquals(Protocolo.STATE, l.leer());
        e = Estado.leer(l.datos());
        assertEquals(1234, e.bufferMs);
        assertEquals(0xFFFFFFFFL, e.cortes);
        assertEquals(0, e.ultimoSeq);
        assertTrue(e.reproduciendo);
    }

    @Test
    public void todosLosTiposEnUnMismoFlujo() throws IOException {
        escritor.hello(new Hello(Protocolo.VERSION, 10, "GT-S5360L", 2, 4000));
        escritor.config(Config.imaAdpcm(44100, 1, 500));
        escritor.track(new Track("t", "a", 1));
        escritor.audio(0, new byte[516], 0, 516);
        escritor.control(Protocolo.CONTROL_PAUSA);
        escritor.state(1, 2, 0, true);
        escritor.ping(7);
        escritor.pong(7);
        escritor.testData(10, true);
        escritor.testResult(18, 1);
        escritor.bye(Protocolo.BYE_NORMAL);
        int[] esperados = {Protocolo.HELLO, Protocolo.CONFIG, Protocolo.TRACK, Protocolo.AUDIO,
                Protocolo.CONTROL, Protocolo.STATE, Protocolo.PING, Protocolo.PONG, Protocolo.TEST_DATA,
                Protocolo.TEST_RESULT, Protocolo.BYE};
        LectorTramas l = lector();
        for (int tipo : esperados) {
            assertEquals(tipo, l.leer());
        }
    }

    // --- Criterio ②: valores inválidos y payloads cortos se rechazan.

    @Test
    public void configInvalidoSeRechaza() {
        int[][] invalidos = {
                {0x02, 0x00, 0x00, 0xAC, 0x44, 0x01, 0x04, 0x00, 0, 0, 0, 0}, // códec 2
                {0x01, 0x00, 0x00, 0x0F, 0x9F, 0x01, 0x04, 0x00, 0, 0, 0, 0}, // 3999 Hz
                {0x01, 0x00, 0x00, 0xBB, 0x81, 0x01, 0x04, 0x00, 0, 0, 0, 0}, // 48001 Hz
                {0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x04, 0x00, 0, 0, 0, 0}, // 4294967295 Hz
                {0x01, 0x00, 0x00, 0xAC, 0x44, 0x00, 0x04, 0x00, 0, 0, 0, 0}, // 0 canales
                {0x01, 0x00, 0x00, 0xAC, 0x44, 0x03, 0x04, 0x00, 0, 0, 0, 0}, // 3 canales
                {0x01, 0x00, 0x00, 0xAC, 0x44, 0x01, 0x00, 0x00, 0, 0, 0, 0}, // 0 muestras
                {0x01, 0x00, 0x00, 0xAC, 0x44, 0x01, 0x03, 0xFF, 0, 0, 0, 0}, // 1023 muestras (impar)
                {0x01, 0x00, 0x00, 0xAC, 0x44, 0x02, 0xFF, 0xFE, 0, 0, 0, 0}, // estéreo de 65534: no cabe
        };
        for (int[] payload : invalidos) {
            try {
                LectorTramas l = lector(trama(Protocolo.CONFIG, payload));
                l.leer();
                Config.leer(l.datos());
                fail("debió rechazar " + Arrays.toString(payload));
            } catch (ProtocoloException esperada) {
                assertTrue(esperada.getMessage().startsWith("CONFIG inválido"));
            } catch (IOException e) {
                fail("se esperaba ProtocoloException: " + e);
            }
        }
    }

    @Test
    public void configInvalidoNoSeConstruye() {
        int[][] invalidos = {{2, 44100, 1, 1024}, {1, 3999, 1, 1024}, {1, 48001, 1, 1024}, {1, 44100, 0, 1024},
                {1, 44100, 3, 1024}, {1, 44100, 1, 1023}, {1, 44100, 1, 0}, {1, 44100, 2, 65534}};
        for (int[] v : invalidos) {
            try {
                new Config(v[0], v[1], v[2], v[3], 500);
                fail("debió rechazar " + Arrays.toString(v));
            } catch (IllegalArgumentException esperada) {
                // ok
            }
        }
    }

    @Test
    public void payloadsCortosSeRechazan() throws IOException {
        byte[][] tramas = {
                trama(Protocolo.CONFIG, 0x01, 0x00, 0x00, 0xAC), // CONFIG cortado
                trama(Protocolo.TRACK, 0x00, 0x05, 'a'), // título más corto que su largo
                trama(Protocolo.STATE, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), // sin "reproduciendo"
                trama(Protocolo.CONTROL), // sin comando
                trama(Protocolo.AUDIO, 0, 0, 1), // seq incompleto
        };
        for (byte[] b : tramas) {
            LectorTramas l = lector(b);
            int tipo = l.leer();
            try {
                switch (tipo) {
                    case Protocolo.CONFIG:
                        Config.leer(l.datos());
                        break;
                    case Protocolo.TRACK:
                        Track.leer(l.datos());
                        break;
                    case Protocolo.STATE:
                        Estado.leer(l.datos());
                        break;
                    case Protocolo.CONTROL:
                        l.datos().readUnsignedByte();
                        break;
                    default:
                        l.datos().readInt();
                        break;
                }
                fail("debió rechazar el tipo " + tipo);
            } catch (ProtocoloException esperada) {
                // ok
            }
        }
    }

    @Test
    public void escritorRechazaValoresFueraDeRango() throws IOException {
        Runnable[] casos = {
                () -> escribir(() -> escritor.audio(-1, new byte[4], 0, 4)),
                () -> escribir(() -> escritor.audio(0x100000000L, new byte[4], 0, 4)),
                () -> escribir(() -> escritor.audio(0, new byte[70000], 0, Protocolo.MAX_PAYLOAD - 3)),
                () -> escribir(() -> escritor.control(256)),
                () -> escribir(() -> escritor.state(-1, 0, 0, true)),
                () -> escribir(() -> escritor.state(0, 0, 0x100000000L, true)),
                () -> new Track("t", null, 0),
                () -> new Track("t", "a", -1),
        };
        for (Runnable caso : casos) {
            try {
                caso.run();
                fail("debió lanzar IllegalArgumentException");
            } catch (IllegalArgumentException esperada) {
                // ok
            }
        }
        assertEquals("nada debe haberse escrito", 0, bytes.size());
        // El escritor sigue sirviendo después de un rechazo.
        escritor.control(Protocolo.CONTROL_PAUSA);
        assertEquals(Protocolo.CONTROL, lector().leer());
    }

    private interface Escritura {
        void ejecutar() throws IOException;
    }

    private static void escribir(Escritura e) {
        try {
            e.ejecutar();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    public void duracionDeBloques() {
        Config c48 = Config.imaAdpcm(48000, 2, 500);
        assertEquals(21, c48.msParaBloques(1));
        assertEquals(3989, c48.msParaBloques(187)); // 3989,3 ms
        assertEquals(1000, Config.imaAdpcm(44100, 1, 500).msParaFrames(44100));
    }
}
