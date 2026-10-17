package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * Emisor y receptor con hilos y sockets TCP de loopback, armados como en las apps: en el receptor,
 * hilo de red, de STATE y de audio (con una SalidaFalsa que suena en tiempo real); en el emisor,
 * SesionEmisor con su lector y un hilo que da pasos al pipeline del tono y envía PING cada 1 s.
 */
public class PuntaAPuntaTest {

    private static final Hello HELLO = new Hello(Protocolo.VERSION, 10, "GT-S5360L", 2, 4000);
    private static final long SEGUNDO = 1000000000L;

    private ServerSocket servidor;
    private Socket cliente;
    private Socket aceptado;
    private volatile boolean activo = true;
    private Thread red;
    private Thread estado;
    private Thread audio;
    private final AtomicReference<Throwable> errorRed = new AtomicReference<Throwable>();
    private final AtomicReference<Throwable> errorOtros = new AtomicReference<Throwable>();

    private final ColaBloques cola = new ColaBloques(188, 1032);
    private final SalidaFalsa salida = new SalidaFalsa(8192, 1024, 0, 48000 * 4);
    private final Reproductor reproductor = new Reproductor(cola, salida, HELLO.capacidadBufferMs);

    @After
    public void cerrar() throws Exception {
        activo = false;
        if (cliente != null) {
            cliente.close();
        }
        if (aceptado != null) {
            aceptado.close();
        }
        if (servidor != null) {
            servidor.close();
        }
        cola.despertar();
        for (Thread t : new Thread[] {red, estado, audio}) {
            if (t != null) {
                t.join(3000);
            }
        }
        assertNull("error en el hilo de STATE o de audio: " + errorOtros.get(), errorOtros.get());
    }

    private void arrancarReceptor() throws IOException {
        servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        red = new Thread(() -> {
            try {
                aceptado = servidor.accept();
                aceptado.setTcpNoDelay(true);
                final SesionReceptor sesion = new SesionReceptor(aceptado.getInputStream(), aceptado.getOutputStream(),
                        HELLO, cola, reproductor, null, Reloj.SISTEMA);
                sesion.iniciar();
                estado = new Thread(() -> {
                    try {
                        while (activo) {
                            long ahora = System.nanoTime();
                            if (!sesion.tickEstado(ahora)) {
                                break;
                            }
                            Thread.sleep(Math.max(1, sesion.nanosHastaEstado(System.nanoTime()) / 1000000L));
                        }
                    } catch (IOException | InterruptedException fin) {
                        // la conexión se cerró
                    } catch (Throwable e) {
                        errorOtros.set(e);
                    }
                }, "estado");
                estado.start();
                try {
                    while (activo) {
                        if (sesion.procesarTrama() >= 0) {
                            break;
                        }
                    }
                } finally {
                    sesion.terminar();
                }
            } catch (Throwable e) {
                errorRed.set(e);
            }
        }, "red");
        audio = new Thread(() -> {
            try {
                long antes = System.nanoTime();
                while (activo) {
                    long ahora = System.nanoTime();
                    salida.avanzar(ahora - antes);
                    antes = ahora;
                    long espera = reproductor.paso(ahora);
                    cola.esperar(Math.min(espera, 5));
                }
                reproductor.cerrar();
            } catch (Throwable e) {
                errorOtros.set(e);
            }
        }, "audio");
        red.start();
        audio.start();
    }

    private SesionEmisor conectar() throws IOException {
        cliente = new Socket(InetAddress.getLoopbackAddress(), servidor.getLocalPort());
        cliente.setTcpNoDelay(true);
        return new SesionEmisor(cliente.getInputStream(), cliente.getOutputStream());
    }

    /** Hilo de E/S del emisor: pipeline + PING cada 1 s, durante {@code nanos}. */
    private static void emitir(SesionEmisor sesion, PipelineEmisor pipeline, long nanos) throws IOException {
        long fin = System.nanoTime() + nanos;
        long proximoPing = 0;
        while (System.nanoTime() - fin < 0) {
            long ahora = System.nanoTime();
            if (ahora - proximoPing >= 0) {
                sesion.enviarPing();
                proximoPing = ahora + SesionEmisor.INTERVALO_PING_NS;
            }
            long espera = pipeline.paso(ahora);
            if (espera == PipelineEmisor.FIN) {
                return;
            }
            if (espera > 0) {
                sesion.esperarEstado(espera);
            }
            sesion.comprobar();
        }
    }

    @Test(timeout = 20000)
    public void tonoSuenaLimpioYAudioInvalidoTerminaConBye2() throws Exception {
        arrancarReceptor();
        SesionEmisor sesion = conectar();
        Hello hello = sesion.esperarHello();
        assertEquals(4000, hello.capacidadBufferMs);

        PipelineEmisor pipeline = new PipelineEmisor(new FuenteTono(48000, 2), Preset.ALTA,
                new Track("Tono de prueba", "440 Hz", 0), PipelineEmisor.OBJETIVO_ARCHIVOS_MS);
        pipeline.conectar(sesion.escritor(), sesion::estado, hello.capacidadBufferMs);
        sesion.setAudioActivo(true);
        emitir(sesion, pipeline, 3 * SEGUNDO);

        // STATE cada 250 ms desde el HELLO.
        assertTrue("STATE recibidos: " + sesion.estadosRecibidos(), sesion.estadosRecibidos() >= 10);
        Estado e = sesion.estado();
        assertTrue("reproduciendo: " + e, e.reproduciendo);
        assertEquals(0, e.cortes);
        assertTrue(e.bufferMs > 1000 && e.bufferMs <= 2100);
        assertTrue(sesion.ultimoRttNs() > 0);
        try {
            sesion.probarEnlace(1, 10, 100);
            fail("la prueba de enlace no debe correr con audio en curso");
        } catch (IllegalStateException esperada) {
            // bien
        }

        // Lo que se escribió en la salida es un 440 Hz limpio.
        int n = salida.largoHistorial;
        assertTrue("muestras escritas: " + n, n > 48000);
        double snr = Senales.snrTono(salida.historial, 2048, n - 2048, 1, 48000, 440);
        assertTrue("SNR " + snr, snr >= 20);
        assertNull(salida.error);
        assertEquals(0, reproductor.cortes());

        // Un AUDIO de largo incorrecto: el receptor contesta BYE(2) y cierra.
        sesion.escritor().audio(Seq.siguiente(pipeline.seqEnviado()), new byte[10], 0, 10);
        try {
            for (int i = 0; i < 100; i++) {
                sesion.esperarEstado(100);
            }
            fail("debió llegar BYE");
        } catch (ByeRecibidoException bye) {
            assertEquals(Protocolo.BYE_PROTOCOLO, bye.codigo);
        }
        red.join(3000);
        assertTrue("el receptor rechazó la trama: " + errorRed.get(), errorRed.get() instanceof ProtocoloException);
        sesion.cerrar();
    }
}
