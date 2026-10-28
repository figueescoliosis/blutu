package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.junit.Test;

public class TramasTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final EscritorTramas escritor = new EscritorTramas(bytes);

    private LectorTramas lector() {
        return new LectorTramas(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static LectorTramas lector(byte[] b) {
        return new LectorTramas(new ByteArrayInputStream(b));
    }

    /** Cabecera cruda: permite armar tramas que el escritor se niega a producir. */
    private static byte[] cabecera(int magic, int tipo, int flags, long largo) {
        return new byte[] {
                (byte) (magic >>> 8), (byte) magic, (byte) tipo, (byte) flags,
                (byte) (largo >>> 24), (byte) (largo >>> 16), (byte) (largo >>> 8), (byte) largo};
    }

    @Test
    public void cabeceraEnBigEndian() throws IOException {
        escritor.ping(0x0102030405060708L);
        byte[] esperado = {0x50, 0x59, 0x07, 0x00, 0x00, 0x00, 0x00, 0x08, 1, 2, 3, 4, 5, 6, 7, 8};
        assertArrayEquals(esperado, bytes.toByteArray());
    }

    @Test
    public void helloEnBytesSegunProtocolo() throws IOException {
        escritor.hello(new Hello(Protocolo.VERSION, 10, "x", 2, 4000));
        byte[] esperado = {
                0x50, 0x59, 0x01, 0x00, 0x00, 0x00, 0x00, 0x0B, // cabecera, largo 11
                0x04, // versión (v4)
                0x00, 0x0A, // apiLevel
                0x00, 0x01, 'x', // nombre UTF
                0x02, // maxCanales
                0x00, 0x00, 0x0F, (byte) 0xA0}; // capacidadBufferMs = 4000
        assertArrayEquals(esperado, bytes.toByteArray());
    }

    @Test
    public void helloDeOtraVersionSeRechazaLeyendoSoloElPrimerByte() throws IOException {
        // Un HELLO "v5" con un formato que la v4 no entiende: solo importa el primer byte.
        byte[] trama = {0x50, 0x59, 0x01, 0x00, 0x00, 0x00, 0x00, 0x03, 0x05, (byte) 0xFF, (byte) 0xFF};
        LectorTramas l = lector(trama);
        assertEquals(Protocolo.HELLO, l.leer());
        try {
            Hello.leer(l.datos());
            fail("debió rechazar la versión 5");
        } catch (VersionIncompatibleException e) {
            assertEquals(5, e.versionRemota);
            assertEquals("no debe leer más allá de la versión", 2, l.datos().available());
        }
    }

    @Test
    public void helloIdaYVuelta() throws IOException {
        Hello enviado = new Hello(Protocolo.VERSION, 10, "Galaxy Y de Ñandú ♪ 😀", 2, 4000);
        escritor.hello(enviado);

        LectorTramas l = lector();
        assertEquals(Protocolo.HELLO, l.leer());
        assertEquals(0, l.flags());
        Hello leido = Hello.leer(l.datos());
        assertEquals(enviado.version, leido.version);
        assertEquals(enviado.apiLevel, leido.apiLevel);
        assertEquals(enviado.nombre, leido.nombre);
        assertEquals(enviado.maxCanales, leido.maxCanales);
        assertEquals(enviado.capacidadBufferMs, leido.capacidadBufferMs);
        assertEquals("el payload debe consumirse completo", 0, l.datos().available());
    }

    @Test
    public void helloConValoresExtremos() throws IOException {
        escritor.hello(new Hello(Protocolo.VERSION, 0xFFFF, "", 0xFF, 0xFFFFFFFFL));
        LectorTramas l = lector();
        l.leer();
        Hello h = Hello.leer(l.datos());
        assertEquals(0xFFFF, h.apiLevel);
        assertEquals("", h.nombre);
        assertEquals(0xFF, h.maxCanales);
        assertEquals(0xFFFFFFFFL, h.capacidadBufferMs);
    }

    @Test
    public void helloDeOtraVersionSeRechaza() throws IOException {
        // Una futura y la v1 (apps 0.2.0, sin CONTROL FIN).
        int[] otras = {Protocolo.VERSION + 1, 1};
        for (int v : otras) {
            escritor.hello(new Hello(v, 10, "otra", 2, 4000));
        }
        LectorTramas l = lector();
        for (int v : otras) {
            assertEquals(Protocolo.HELLO, l.leer());
            try {
                Hello.leer(l.datos());
                fail("debió rechazar la versión " + v);
            } catch (VersionIncompatibleException e) {
                assertEquals(v, e.versionRemota);
            }
        }
    }

    @Test
    public void pingYPongIdaYVuelta() throws IOException {
        long[] valores = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 123456789012345L};
        for (long v : valores) {
            escritor.ping(v);
            escritor.pong(v);
        }
        LectorTramas l = lector();
        for (long v : valores) {
            assertEquals(Protocolo.PING, l.leer());
            assertEquals(8, l.largo());
            assertEquals(v, l.datos().readLong());
            assertEquals(Protocolo.PONG, l.leer());
            assertEquals(v, l.datos().readLong());
        }
    }

    @Test
    public void testDataIdaYVuelta() throws IOException {
        int[] largos = {0, 1, SesionEmisor.LARGO_TRAMA_PRUEBA, Protocolo.MAX_PAYLOAD};
        for (int largo : largos) {
            escritor.testData(largo, false);
        }
        escritor.testData(10, true);

        LectorTramas l = lector();
        for (int largo : largos) {
            assertEquals(Protocolo.TEST_DATA, l.leer());
            assertEquals(largo, l.largo());
            assertEquals(0, l.flags() & Protocolo.FLAG_ULTIMA);
            byte[] payload = new byte[largo];
            l.datos().readFully(payload);
            assertArrayEquals("el relleno son ceros", new byte[largo], payload);
        }
        assertEquals(Protocolo.TEST_DATA, l.leer());
        assertEquals(Protocolo.FLAG_ULTIMA, l.flags());
    }

    @Test
    public void testResultIdaYVuelta() throws IOException {
        escritor.testResult(0x123456789AL, 0xFFFFFFFFL);
        escritor.testResult(0, 0);
        LectorTramas l = lector();
        assertEquals(Protocolo.TEST_RESULT, l.leer());
        assertEquals(12, l.largo());
        assertEquals(0x123456789AL, l.datos().readLong());
        assertEquals(0xFFFFFFFFL, l.datos().readInt() & 0xFFFFFFFFL);
        assertEquals(Protocolo.TEST_RESULT, l.leer());
        assertEquals(0L, l.datos().readLong());
        assertEquals(0, l.datos().readInt());
    }

    @Test
    public void byeIdaYVuelta() throws IOException {
        int[] codigos = {Protocolo.BYE_NORMAL, Protocolo.BYE_VERSION, Protocolo.BYE_PROTOCOLO, 0xFF};
        for (int c : codigos) {
            escritor.bye(c);
        }
        LectorTramas l = lector();
        for (int c : codigos) {
            assertEquals(Protocolo.BYE, l.leer());
            assertEquals(1, l.largo());
            assertEquals(c, l.datos().readUnsignedByte());
        }
    }

    @Test
    public void payloadNoLeidoNoDesalineaElFlujo() throws IOException {
        escritor.hello(new Hello(Protocolo.VERSION, 10, "x", 2, 4000));
        escritor.testData(100, false);
        escritor.ping(42);
        LectorTramas l = lector();
        assertEquals(Protocolo.HELLO, l.leer());
        assertEquals(Protocolo.TEST_DATA, l.leer());
        assertEquals(Protocolo.PING, l.leer());
        assertEquals(42, l.datos().readLong());
    }

    @Test
    public void tipoDesconocidoSeLeeYSeSalta() throws IOException {
        DataOutputStream d = new DataOutputStream(bytes);
        d.write(cabecera(Protocolo.MAGIC, 0x7F, 0x80, 3));
        d.write(new byte[] {9, 9, 9});
        escritor.ping(7);
        LectorTramas l = lector();
        assertEquals(0x7F, l.leer());
        assertEquals(0x80, l.flags());
        assertEquals(3, l.largo());
        assertEquals(Protocolo.PING, l.leer());
        assertEquals(7, l.datos().readLong());
    }

    @Test
    public void magicInvalidoSeRechaza() throws IOException {
        byte[] b = cabecera(0x5058, Protocolo.PING, 0, 8);
        try {
            lector(Arrays.copyOf(b, b.length + 8)).leer();
            fail("debió rechazar el magic");
        } catch (ProtocoloException esperada) {
            assertTrue(esperada.getMessage().contains("magic"));
        }
    }

    @Test
    public void largoExcesivoSeRechazaSinLeerElPayload() throws IOException {
        long[] largos = {Protocolo.MAX_PAYLOAD + 1L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL};
        for (long largo : largos) {
            try {
                lector(cabecera(Protocolo.MAGIC, Protocolo.TEST_DATA, 0, largo)).leer();
                fail("debió rechazar el largo " + largo);
            } catch (ProtocoloException esperada) {
                assertTrue(esperada.getMessage().contains("largo"));
            }
        }
    }

    @Test
    public void flujoTruncadoSeRechaza() throws IOException {
        escritor.ping(1);
        byte[] completa = bytes.toByteArray();
        for (int n = 1; n < completa.length; n++) {
            try {
                lector(Arrays.copyOf(completa, n)).leer();
                fail("debió fallar con " + n + " bytes");
            } catch (EOFException esperada) {
                // ok
            }
        }
    }

    @Test(timeout = 5000)
    public void readQueDevuelveCeroSeTrataComoFinDelFlujo() throws IOException {
        // Un flujo que, tras 3 bytes, devuelve 0 para siempre (como algunos stacks Bluetooth de 2.3).
        InputStream ceros = new InputStream() {
            private int entregados;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int desde, int n) {
                if (entregados >= 3) {
                    return 0;
                }
                b[desde] = (byte) (entregados == 0 ? 0x50 : 0x59);
                entregados++;
                return 1;
            }
        };
        try {
            new LectorTramas(ceros).leer();
            fail("debió lanzar EOFException");
        } catch (EOFException esperada) {
            assertEquals("el flujo terminó a mitad de una trama", esperada.getMessage());
        }
    }

    @Test
    public void finDeFlujoEntreTramas() throws IOException {
        try {
            lector(new byte[0]).leer();
            fail("debió lanzar EOFException");
        } catch (EOFException esperada) {
            assertEquals("conexión cerrada", esperada.getMessage());
        }
    }

    @Test
    public void payloadMasCortoQueElMensajeSeDetecta() throws IOException {
        // Un PING con payload de 4 bytes en vez de 8.
        byte[] b = Arrays.copyOf(cabecera(Protocolo.MAGIC, Protocolo.PING, 0, 4), 12);
        LectorTramas l = lector(b);
        assertEquals(Protocolo.PING, l.leer());
        try {
            l.datos().readLong();
            fail("debió lanzar ProtocoloException");
        } catch (ProtocoloException esperada) {
            assertTrue(esperada.getMessage().contains("payload"));
        }
        // Un BYE sin su byte de código.
        l = lector(cabecera(Protocolo.MAGIC, Protocolo.BYE, 0, 0));
        assertEquals(Protocolo.BYE, l.leer());
        try {
            l.datos().readUnsignedByte();
            fail("debió lanzar ProtocoloException");
        } catch (ProtocoloException esperada) {
            // ok
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void escritorRechazaTestDataDemasiadoGrande() throws IOException {
        escritor.testData(Protocolo.MAX_PAYLOAD + 1, false);
    }

    @Test
    public void escritorRechazaHelloQueNoCabe() {
        StringBuilder nombre = new StringBuilder();
        for (int i = 0; i < 0xFFFF; i++) {
            nombre.append('a');
        }
        try {
            escritor.hello(new Hello(Protocolo.VERSION, 10, nombre.toString(), 2, 4000));
            fail("un HELLO de más de 64 KiB no debe enviarse");
        } catch (IOException esperada) {
            assertEquals("no debe salir nada", 0, bytes.size());
        }
    }

    @Test
    public void escritorSigueFuncionandoTrasUnRechazo() throws IOException {
        try {
            escritor.testData(-1, false);
            fail();
        } catch (IllegalArgumentException esperada) {
            // ok
        }
        escritor.bye(Protocolo.BYE_NORMAL);
        LectorTramas l = lector();
        assertEquals(Protocolo.BYE, l.leer());
        assertEquals(1, l.largo());
    }
}
