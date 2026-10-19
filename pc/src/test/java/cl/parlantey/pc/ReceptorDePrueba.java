package cl.parlantey.pc;

import cl.parlantey.core.ColaBloques;
import cl.parlantey.core.Hello;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.Reloj;
import cl.parlantey.core.Reproductor;
import cl.parlantey.core.SesionReceptor;
import cl.parlantey.core.Track;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * El receptor real del core (SesionReceptor + Reproductor) con una salida a tiempo real, y un
 * {@link Transporte.Conector} que lo conecta por tuberías en memoria. La cola y el reproductor
 * sobreviven a las reconexiones, como en el Galaxy Y.
 */
final class ReceptorDePrueba implements Transporte.Conector {
    final ColaBloques cola = new ColaBloques(188, 1032);
    final SalidaTiempoReal salida = new SalidaTiempoReal();
    final Reproductor reproductor = new Reproductor(cola, salida, 4000);
    volatile int version = Protocolo.VERSION;
    volatile int conexiones;
    volatile int byeRecibido = -1;
    volatile boolean fallarConexion;
    final List<String> tracks = new ArrayList<>();
    private volatile boolean fin;
    private Tuberia haciaReceptor;
    private Tuberia haciaEmisor;
    private final Thread audio;

    ReceptorDePrueba() {
        audio = new Thread(() -> {
            while (!fin) {
                long espera = reproductor.paso(System.nanoTime());
                if (espera > 0) {
                    cola.esperarTras(cola.eventos(), Math.min(espera, 20));
                }
            }
        }, "receptor-audio");
        audio.setDaemon(true);
        audio.start();
    }

    @Override
    public List<Dispositivo> vinculados() {
        List<Dispositivo> l = new ArrayList<>();
        l.add(new Dispositivo("GT-S5360L", 0x001122334455L));
        return l;
    }

    @Override
    public synchronized Transporte conectar(Dispositivo d) throws IOException {
        if (fallarConexion) {
            throw new IOException("sin respuesta");
        }
        conexiones++;
        Tuberia ar = new Tuberia(1 << 18);
        Tuberia ae = new Tuberia(1 << 18);
        haciaReceptor = ar;
        haciaEmisor = ae;
        SesionReceptor sesion = new SesionReceptor(ar.entrada, ae.salida, new Hello(version, 10, "GT-S5360L", 2, 4000),
                cola, reproductor, new SesionReceptor.Oyente() {
                    @Override
                    public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
                    }

                    @Override
                    public void track(Track track) {
                        synchronized (tracks) {
                            tracks.add(track.titulo);
                        }
                    }
                }, Reloj.SISTEMA);
        sesion.iniciar();
        Thread red = new Thread(() -> {
            try {
                while (true) {
                    int bye = sesion.procesarTrama();
                    if (bye >= 0) {
                        byeRecibido = bye;
                        break;
                    }
                }
            } catch (IOException e) {
                // conexión cortada
            } finally {
                sesion.terminar();
                ar.cerrar();
                ae.cerrar();
            }
        }, "receptor-red");
        red.setDaemon(true);
        red.start();
        Thread state = new Thread(() -> {
            try {
                while (sesion.tickEstado(System.nanoTime())) {
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                // fin
            } finally {
                ar.cerrar();
                ae.cerrar();
            }
        }, "receptor-state");
        state.setDaemon(true);
        state.start();
        return new Transporte() {
            @Override
            public java.io.InputStream entrada() {
                return ae.entrada;
            }

            @Override
            public java.io.OutputStream salida() {
                return ar.salida;
            }

            @Override
            public void cerrar() {
                ar.cerrar();
                ae.cerrar();
            }
        };
    }

    @Override
    public void cancelar() {
    }

    /** Se corta el enlace (como apagar el Bluetooth un momento). */
    synchronized void cortar() {
        if (haciaReceptor != null) {
            haciaReceptor.cerrar();
            haciaEmisor.cerrar();
        }
    }

    void terminar() {
        fin = true;
        cortar();
    }
}
