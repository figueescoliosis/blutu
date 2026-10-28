package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Sesión completa emisor ↔ receptor sobre un socket TCP de loopback (en lugar de RFCOMM). */
public class SesionTest {

    private static final Hello HELLO = new Hello(Protocolo.VERSION, 10, "GT-S5360", 2, 4000);

    private ServerSocket servidor;
    private Socket cliente;
    private Thread hiloReceptor;
    private final AtomicReference<Object> salidaReceptor = new AtomicReference<Object>();

    /** Lo que hace el receptor con el socket aceptado. */
    private interface LadoReceptor {
        Object atender(Socket s) throws Exception;
    }

    @Before
    public void abrir() throws IOException {
        servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @After
    public void cerrar() throws Exception {
        if (cliente != null) {
            cliente.close();
        }
        servidor.close();
        if (hiloReceptor != null) {
            hiloReceptor.join(5000);
        }
    }

    private void arrancarReceptor(final LadoReceptor lado) {
        hiloReceptor = new Thread(() -> {
            try (Socket s = servidor.accept()) {
                salidaReceptor.set(lado.atender(s));
            } catch (Throwable e) {
                salidaReceptor.set(e);
            }
        });
        hiloReceptor.start();
    }

    private SesionEmisor conectar() throws IOException {
        cliente = new Socket(InetAddress.getLoopbackAddress(), servidor.getLocalPort());
        cliente.setSoTimeout(10000);
        return new SesionEmisor(cliente.getInputStream(), cliente.getOutputStream());
    }

    private Object esperarReceptor() throws InterruptedException {
        hiloReceptor.join(10000);
        assertTrue("el receptor no terminó", !hiloReceptor.isAlive());
        return salidaReceptor.get();
    }

    @Test(timeout = 20000)
    public void pruebaDeEnlaceCompleta() throws Exception {
        final AtomicLong bytesOyente = new AtomicLong(-1);
        final AtomicLong msOyente = new AtomicLong(-1);
        arrancarReceptor(s -> SesionReceptor.atender(s.getInputStream(), s.getOutputStream(), HELLO,
                new SesionReceptor.Oyente() {
                    @Override
                    public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
                        bytesOyente.set(bytesRecibidos);
                        msOyente.set(duracionMs);
                    }

                    @Override
                    public void track(Track track) {
                    }
                }));

        SesionEmisor sesion = conectar();
        Hello hello = sesion.esperarHello();
        assertEquals(Protocolo.VERSION, hello.version);
        assertEquals("GT-S5360", hello.nombre);
        assertEquals(10, hello.apiLevel);
        assertEquals(2, hello.maxCanales);
        assertEquals(4000, hello.capacidadBufferMs);

        assertTrue(sesion.ping() > 0);

        ResultadoPrueba r = sesion.probarEnlace(5, 300, SesionEmisor.LARGO_TRAMA_PRUEBA);
        assertEquals(5, r.pings);
        assertTrue(r.rttMinNs > 0 && r.rttMinNs <= r.rttPromNs && r.rttPromNs <= r.rttMaxNs);
        long bytesTrama = Protocolo.LARGO_CABECERA + SesionEmisor.LARGO_TRAMA_PRUEBA;
        assertTrue("al menos una trama más la última", r.bytesEnviados >= 2 * bytesTrama);
        assertEquals(0, r.bytesEnviados % bytesTrama);
        assertEquals("el receptor contó todo lo enviado", r.bytesEnviados, r.bytesRecibidos);
        assertTrue(r.duracionReceptorMs >= 250 && r.duracionReceptorMs <= r.duracionEmisorMs);
        assertTrue(r.kBpsReceptor() > 0 && r.kBpsEmisor() > 0);

        // Una segunda prueba en la misma conexión cuenta desde cero: bytes y duración.
        ResultadoPrueba r2 = sesion.probarEnlace(1, 50, 100);
        assertEquals(r2.bytesEnviados, r2.bytesRecibidos);
        assertTrue("la duración no debe arrastrar la prueba anterior",
                r2.duracionReceptorMs < r.duracionReceptorMs && r2.duracionReceptorMs <= r2.duracionEmisorMs);

        sesion.despedir(Protocolo.BYE_NORMAL);
        assertEquals(Protocolo.BYE_NORMAL, esperarReceptor());
        assertEquals(r2.bytesRecibidos, bytesOyente.get());
        assertEquals(r2.duracionReceptorMs, msOyente.get());
    }

    @Test(timeout = 20000)
    public void receptorIgnoraTiposDesconocidos() throws Exception {
        arrancarReceptor(s -> SesionReceptor.atender(s.getInputStream(), s.getOutputStream(), HELLO, null));
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        // Una trama de un tipo futuro (0x7F) con payload arbitrario.
        cliente.getOutputStream().write(new byte[] {0x50, 0x59, 0x7F, 0x00, 0, 0, 0, 3, 1, 2, 3});
        assertTrue(sesion.ping() > 0);
        sesion.despedir(Protocolo.BYE_NORMAL);
        assertEquals(Protocolo.BYE_NORMAL, esperarReceptor());
    }

    @Test(timeout = 20000)
    public void emisorDescartaPongViejosYTramasDesconocidas() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            LectorTramas l = new LectorTramas(s.getInputStream());
            e.hello(HELLO);
            // ping() suelto y el PING con que empieza probarEnlace(1, ...).
            for (int i = 0; i < 2; i++) {
                assertEquals(Protocolo.PING, l.leer());
                long t = l.datos().readLong();
                e.pong(t - 1); // PONG de un PING anterior: debe descartarse
                s.getOutputStream().write(new byte[] {0x50, 0x59, 0x7E, 0x00, 0, 0, 0, 2, 7, 7}); // tipo futuro
                e.pong(t);
            }
            long bytes = 0;
            while (true) {
                assertEquals(Protocolo.TEST_DATA, l.leer());
                bytes += Protocolo.LARGO_CABECERA + l.largo();
                if ((l.flags() & Protocolo.FLAG_ULTIMA) != 0) {
                    break;
                }
            }
            s.getOutputStream().write(new byte[] {0x50, 0x59, 0x7F, 0x00, 0, 0, 0, 0}); // antes del resultado
            e.testResult(bytes, 42);
            return null;
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        assertTrue(sesion.ping() > 0);
        ResultadoPrueba r = sesion.probarEnlace(1, 20, 100);
        assertEquals(1, r.pings);
        assertEquals(r.bytesEnviados, r.bytesRecibidos);
        assertEquals(42, r.duracionReceptorMs);
        assertNull(esperarReceptor());
    }

    @Test(timeout = 20000)
    public void emisorRecibeElMicrofono() throws Exception {
        final Config mic = new Config(Protocolo.CODEC_IMA_ADPCM, Protocolo.TASA_MIC, 1, Protocolo.MUESTRAS_MIC,
                Protocolo.OBJETIVO_MIC_MS, true);
        final byte[] enviado = new byte[mic.bytesBloque()];
        short[] pcm = new short[Protocolo.MUESTRAS_MIC];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / Protocolo.TASA_MIC));
        }
        new CodificadorIma(1, Protocolo.MUESTRAS_MIC).codificarBloque(pcm, 0, pcm.length, enviado, 0);
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            LectorTramas l = new LectorTramas(s.getInputStream());
            e.hello(HELLO);
            e.micConfig(mic);
            for (int seq = 0; seq < 3; seq++) {
                e.micAudio(seq, enviado, 0, enviado.length);
            }
            assertEquals(Protocolo.PING, l.leer());
            e.pong(l.datos().readLong()); // llega después del micrófono: sirve de sincronización
            assertEquals(Protocolo.SEQ_NINGUNO, e.ultimoSeqAudio()); // MIC_AUDIO no cuenta como AUDIO
            return null;
        });
        ColaBloques cola = new ColaBloques(8, mic.bytesBloque());
        SesionEmisor sesion = conectar();
        sesion.recibirMicrofono(cola);
        sesion.esperarHello();
        assertTrue(sesion.ping() > 0);
        assertNull(esperarReceptor());

        assertEquals(mic, cola.config());
        assertEquals(3, cola.enCola());
        assertEquals(2, cola.ultimoSeq());
        ColaBloques.Bloque b = cola.sacar(cola.generacion());
        assertEquals(enviado.length, b.largo);
        assertTrue(java.util.Arrays.equals(enviado, java.util.Arrays.copyOf(b.datos, b.largo)));
    }

    @Test(timeout = 20000)
    public void emisorSinColaDescartaElMicrofono() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            LectorTramas l = new LectorTramas(s.getInputStream());
            e.hello(HELLO);
            e.micConfig(Config.imaAdpcm(Protocolo.TASA_MIC, 1, Protocolo.OBJETIVO_MIC_MS, true));
            e.micAudio(0, new byte[4 + 512], 0, 516);
            assertEquals(Protocolo.PING, l.leer());
            e.pong(l.datos().readLong());
            return null;
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        assertTrue(sesion.ping() > 0); // la sesión sigue viva
        assertNull(esperarReceptor());
    }

    @Test(timeout = 20000)
    public void versionDistintaTerminaConBye() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            e.hello(new Hello(Protocolo.VERSION + 1, 10, "receptor del futuro", 2, 4000));
            LectorTramas l = new LectorTramas(s.getInputStream());
            assertEquals(Protocolo.BYE, l.leer());
            return l.datos().readUnsignedByte();
        });
        SesionEmisor sesion = conectar();
        try {
            sesion.esperarHello();
            fail("debió rechazar la versión futura");
        } catch (VersionIncompatibleException e) {
            assertEquals(Protocolo.VERSION + 1, e.versionRemota);
        }
        assertEquals(Protocolo.BYE_VERSION, esperarReceptor());
    }

    @Test(timeout = 20000)
    public void byeDelReceptorCortaLaEspera() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            e.hello(HELLO);
            LectorTramas l = new LectorTramas(s.getInputStream());
            assertEquals(Protocolo.PING, l.leer());
            e.bye(Protocolo.BYE_PROTOCOLO);
            return null;
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        try {
            sesion.ping();
            fail("debió lanzar ByeRecibidoException");
        } catch (ByeRecibidoException e) {
            assertEquals(Protocolo.BYE_PROTOCOLO, e.codigo);
        }
        assertNull(esperarReceptor());
    }

    @Test(timeout = 20000)
    public void corteSinByeTerminaLaSesionDelReceptor() throws Exception {
        arrancarReceptor(s -> SesionReceptor.atender(s.getInputStream(), s.getOutputStream(), HELLO, null));
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        cliente.close();
        assertTrue(esperarReceptor() instanceof EOFException);
    }

    @Test(timeout = 20000)
    public void tramaCorruptaTerminaLaSesionDelReceptor() throws Exception {
        arrancarReceptor(s -> SesionReceptor.atender(s.getInputStream(), s.getOutputStream(), HELLO, null));
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        cliente.getOutputStream().write(new byte[] {0x12, 0x34, 0x07, 0x00, 0, 0, 0, 8});
        assertTrue(esperarReceptor() instanceof ProtocoloException);
    }

    @Test(timeout = 20000)
    public void payloadCortoEsErrorDeProtocoloEnElReceptor() throws Exception {
        arrancarReceptor(s -> SesionReceptor.atender(s.getInputStream(), s.getOutputStream(), HELLO, null));
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        // PING con 2 bytes de payload en vez de 8.
        cliente.getOutputStream().write(new byte[] {0x50, 0x59, 0x07, 0x00, 0, 0, 0, 2, 1, 2});
        assertTrue(esperarReceptor() instanceof ProtocoloException);
    }

    @Test(timeout = 20000)
    public void primeraTramaQueNoEsHelloSeRechaza() throws Exception {
        arrancarReceptor(s -> {
            new EscritorTramas(s.getOutputStream()).ping(1);
            return null;
        });
        SesionEmisor sesion = conectar();
        try {
            sesion.esperarHello();
            fail("debió rechazar una primera trama distinta de HELLO");
        } catch (ProtocoloException esperada) {
            assertTrue(esperada.getMessage().contains("HELLO"));
        }
    }

    @Test(timeout = 20000)
    public void stateIntercaladoConPongYResultado() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            LectorTramas l = new LectorTramas(s.getInputStream());
            e.hello(HELLO);
            for (int i = 0; i < 2; i++) { // ping() y el PING de probarEnlace(1, ...)
                assertEquals(Protocolo.PING, l.leer());
                long t = l.datos().readLong();
                e.state(100 + i, 0, 7, true);
                e.pong(t - 1);
                e.state(200 + i, 0, 7, true);
                e.pong(t);
            }
            long bytes = 0;
            while (true) {
                assertEquals(Protocolo.TEST_DATA, l.leer());
                bytes += Protocolo.LARGO_CABECERA + l.largo();
                if ((l.flags() & Protocolo.FLAG_ULTIMA) != 0) {
                    break;
                }
            }
            e.state(300, 1, 8, false);
            e.testResult(bytes, 42);
            e.state(400, 2, 9, true);
            assertEquals(Protocolo.BYE, l.leer());
            return l.datos().readUnsignedByte();
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        assertNull(sesion.estado());
        assertTrue(sesion.ping() > 0);
        ResultadoPrueba r = sesion.probarEnlace(1, 20, 100);
        assertEquals(r.bytesEnviados, r.bytesRecibidos);
        assertEquals(42, r.duracionReceptorMs);
        while (sesion.estadosRecibidos() < 6) {
            sesion.esperarEstado(1000);
        }
        Estado e = sesion.estado();
        assertEquals(400, e.bufferMs);
        assertEquals(2, e.cortes);
        assertEquals(9, e.ultimoSeq);
        assertTrue(e.reproduciendo);
        assertTrue(sesion.ultimoRttNs() > 0);
        sesion.despedir(Protocolo.BYE_NORMAL);
        assertEquals(Protocolo.BYE_NORMAL, esperarReceptor());
        sesion.cerrar();
    }

    @Test(timeout = 20000)
    public void watchdogDelEmisorConRelojVirtual() throws Exception {
        final java.util.concurrent.CountDownLatch enviarState = new java.util.concurrent.CountDownLatch(1);
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            e.hello(HELLO);
            enviarState.await();
            e.state(0, 0, Protocolo.SEQ_NINGUNO, false);
            new LectorTramas(s.getInputStream()).leer(); // hasta que el emisor cierre
            return null;
        });
        final long[] ahora = {1000 * SesionEmisor.LIMITE_SILENCIO_NS};
        cliente = new Socket(InetAddress.getLoopbackAddress(), servidor.getLocalPort());
        SesionEmisor sesion = new SesionEmisor(cliente.getInputStream(), cliente.getOutputStream(), () -> ahora[0]);
        assertTrue("antes del HELLO no vence", !sesion.vencida(Long.MAX_VALUE));
        sesion.esperarHello();
        long t0 = ahora[0];
        assertTrue(!sesion.vencida(t0 + SesionEmisor.LIMITE_SILENCIO_NS));
        assertTrue(sesion.vencida(t0 + SesionEmisor.LIMITE_SILENCIO_NS + 1));
        ahora[0] = t0 + 3 * SesionEmisor.LIMITE_SILENCIO_NS;
        enviarState.countDown();
        assertTrue(sesion.esperarEstadoTras(0, 5000)); // aunque el STATE llegue antes de la llamada
        assertEquals(ahora[0], sesion.nanosUltimaTrama());
        assertTrue("una trama nueva reinicia el watchdog", !sesion.vencida(ahora[0] + 1));
        sesion.cerrar();
        assertTrue(sesion.cerrada());
        esperarReceptor();
    }

    @Test(timeout = 20000)
    public void byeDuranteLaEsperaDeStateSeRelanza() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            e.hello(HELLO);
            e.state(10, 0, 0, true);
            e.bye(Protocolo.BYE_NORMAL);
            return null;
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        try {
            for (int i = 0; i < 50; i++) {
                sesion.esperarEstado(200);
            }
            fail("debió lanzar ByeRecibidoException");
        } catch (ByeRecibidoException e) {
            assertEquals(Protocolo.BYE_NORMAL, e.codigo);
        }
        assertTrue(sesion.causa() instanceof ByeRecibidoException);
        assertEquals(10, sesion.estado().bufferMs); // el último STATE sigue disponible
        try {
            sesion.ping();
            fail("la sesión ya terminó");
        } catch (ByeRecibidoException esperada) {
            // bien
        }
        sesion.cerrar();
        assertNull(esperarReceptor());
    }

    @Test(timeout = 20000)
    public void cerrarDesbloqueaAlLector() throws Exception {
        arrancarReceptor(s -> {
            new EscritorTramas(s.getOutputStream()).hello(HELLO);
            try {
                new LectorTramas(s.getInputStream()).leer();
            } catch (IOException fin) {
                // el emisor cerró
            }
            return null;
        });
        SesionEmisor sesion = conectar();
        sesion.esperarHello();
        long t = System.nanoTime();
        sesion.cerrar();
        assertTrue("cerrar no debe esperar", System.nanoTime() - t < 2000000000L);
        assertTrue(sesion.causa() != null);
        try {
            sesion.esperarEstado(10);
            fail("la sesión está cerrada");
        } catch (IOException esperada) {
            // bien
        }
        assertNull(esperarReceptor());
    }

    /** Un flujo cuyo close() no desbloquea la lectura (como algunos sockets Bluetooth). */
    private static final class FlujoTerco extends java.io.InputStream {
        private final byte[] inicio;
        private int pos;

        FlujoTerco(byte[] inicio) {
            this.inicio = inicio;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int desde, int n) throws IOException {
            if (pos < inicio.length) {
                int k = Math.min(n, inicio.length - pos);
                System.arraycopy(inicio, pos, b, desde, k);
                pos += k;
                return k;
            }
            try {
                wait(); // nunca llega nada más y close() no lo despierta
            } catch (InterruptedException e) {
                throw new IOException("interrumpido");
            }
            return -1;
        }
    }

    @Test(timeout = 20000)
    public void cerrarDespiertaAlQueEsperaAunqueElLectorSigaBloqueado() throws Exception {
        java.io.ByteArrayOutputStream hello = new java.io.ByteArrayOutputStream();
        new EscritorTramas(hello).hello(HELLO);
        final SesionEmisor sesion = new SesionEmisor(new FlujoTerco(hello.toByteArray()),
                new java.io.ByteArrayOutputStream());
        sesion.esperarHello();
        final AtomicReference<Object> resultado = new AtomicReference<Object>();
        final long[] duracion = {0};
        Thread esperando = new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                sesion.ping(); // el PONG nunca llega
                resultado.set("sin error");
            } catch (IOException e) {
                resultado.set(e);
            }
            duracion[0] = System.nanoTime() - t0;
        });
        esperando.start();
        Thread.sleep(200);
        sesion.cerrar();
        esperando.join(10000);
        assertTrue(resultado.get() instanceof IOException);
        assertTrue("ping debió terminar al cerrar, no a los 5 s: " + duracion[0] / 1000000 + " ms",
                duracion[0] < 3000000000L);
    }
}
