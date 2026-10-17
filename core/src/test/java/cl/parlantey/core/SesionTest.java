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
                (bytesRecibidos, duracionMs) -> {
                    bytesOyente.set(bytesRecibidos);
                    msOyente.set(duracionMs);
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

        // Una segunda prueba en la misma conexión cuenta desde cero.
        ResultadoPrueba r2 = sesion.probarEnlace(1, 50, 100);
        assertEquals(r2.bytesEnviados, r2.bytesRecibidos);

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
        // Una trama de un tipo futuro (CONFIG, 0x02) con payload arbitrario.
        cliente.getOutputStream().write(new byte[] {0x50, 0x59, 0x02, 0x00, 0, 0, 0, 3, 1, 2, 3});
        assertTrue(sesion.ping() > 0);
        sesion.despedir(Protocolo.BYE_NORMAL);
        assertEquals(Protocolo.BYE_NORMAL, esperarReceptor());
    }

    @Test(timeout = 20000)
    public void versionDistintaTerminaConBye() throws Exception {
        arrancarReceptor(s -> {
            EscritorTramas e = new EscritorTramas(s.getOutputStream());
            e.hello(new Hello(2, 10, "receptor del futuro", 2, 4000));
            LectorTramas l = new LectorTramas(s.getInputStream());
            assertEquals(Protocolo.BYE, l.leer());
            return l.datos().readUnsignedByte();
        });
        SesionEmisor sesion = conectar();
        try {
            sesion.esperarHello();
            fail("debió rechazar la versión 2");
        } catch (VersionIncompatibleException e) {
            assertEquals(2, e.versionRemota);
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
}
