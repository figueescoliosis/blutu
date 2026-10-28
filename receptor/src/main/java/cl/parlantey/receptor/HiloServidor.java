package cl.parlantey.receptor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;

import cl.parlantey.core.ColaBloques;
import cl.parlantey.core.Hello;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.ProtocoloException;
import cl.parlantey.core.Reproductor;
import cl.parlantey.core.SesionReceptor;
import cl.parlantey.core.Track;

/**
 * Hilo de red del receptor: publica el servicio RFCOMM, acepta una conexión a la vez y la atiende
 * con {@link SesionReceptor} (este hilo lee las tramas; un {@link HiloEstado} envía STATE cada
 * 250 ms y vigila que lleguen tramas). Al perderse la conexión vuelve a {@code accept()} solo.
 */
final class HiloServidor extends Thread {

    /** Avisos al servicio. Se llaman desde este hilo y no deben bloquear. */
    interface Oyente {
        void esperando();

        void conectado(String nombre);

        void desconectado(int mensaje, String detalle);

        void pruebaTerminada(long bytesRecibidos, long duracionMs);

        void track(Track track);

        void versionIncompatible();

        void bluetoothApagado();

        void error(String donde, Throwable t);
    }

    private static final long ESPERA_MAX_MS = 10000;
    private static final long ESPERA_DESPEDIDA_NS = 1000000000L;

    private final BluetoothAdapter adaptador;
    private final Hello hello;
    private final ColaBloques cola;
    private final Reproductor reproductor;
    private final Oyente oyente;

    private volatile boolean activo = true;
    /** "Detener receptor": cada conexión (también una recién aceptada) se despide con BYE(0). */
    private volatile boolean despidiendo;
    /** Desde que accept() devuelve un socket hasta que se cierra. */
    private volatile boolean atendiendo;
    private volatile Closeable abierto;
    private volatile SesionReceptor sesion;

    HiloServidor(BluetoothAdapter adaptador, Hello hello, ColaBloques cola, Reproductor reproductor, Oyente oyente) {
        super("ParlanteY-servidor");
        this.adaptador = adaptador;
        this.hello = hello;
        this.cola = cola;
        this.reproductor = reproductor;
        this.oyente = oyente;
    }

    /** Detiene el hilo. Cerrar el socket desbloquea {@code accept()} o la lectura en curso. */
    void detener() {
        activo = false;
        cerrar(abierto);
        interrupt();
    }

    /**
     * "Detener receptor": BYE(0) al emisor conectado (si hay), espera hasta 1 s a que el emisor
     * cierre (así el BYE llega antes que el cierre del socket) y detiene el hilo. Bloquea: llamarlo
     * fuera del hilo principal.
     */
    void despedirYDetener() {
        despidiendo = true;
        SesionReceptor s = sesion;
        if (s != null) {
            try {
                s.despedir();
            } catch (IOException ignorada) {
                // Se cierra igual.
            }
        }
        // Una conexión en curso (también una recién aceptada, que se despide sola en atender)
        // termina cuando el emisor cierra al recibir el BYE: no cerrar el socket antes.
        long fin = System.nanoTime() + ESPERA_DESPEDIDA_NS;
        while (atendiendo && System.nanoTime() - fin < 0) {
            dormir(50);
        }
        detener();
    }

    @Override
    public void run() {
        int fallos = 0;
        while (activo) {
            BluetoothSocket socket;
            BluetoothServerSocket servidor = null;
            try {
                servidor = abrirServidor();
                abierto = servidor;
                if (!activo) {
                    break;
                }
                oyente.esperando();
                socket = servidor.accept();
                atendiendo = true;
            } catch (IOException e) {
                if (!activo) {
                    break;
                }
                if (!adaptador.isEnabled()) {
                    oyente.bluetoothApagado();
                    break;
                }
                fallos++;
                oyente.desconectado(R.string.evento_servidor, String.valueOf(e.getMessage()));
                dormir(Math.min(ESPERA_MAX_MS, 1000L << Math.min(fallos - 1, 4)));
                continue;
            } finally {
                // Una sola conexión a la vez: el servidor se cierra mientras se atiende.
                cerrar(servidor);
                abierto = null;
            }
            fallos = 0;
            abierto = socket;
            if (activo) {
                try {
                    atender(socket);
                } catch (Throwable t) {
                    // Cualquier falla de una conexión no debe tumbar el servidor: vuelve a accept().
                    oyente.error("conexión", t);
                }
            }
            cerrar(socket);
            abierto = null;
            atendiendo = false;
            if (despidiendo) {
                break; // "Detener receptor": no aceptar otra conexión
            }
        }
    }

    private BluetoothServerSocket abrirServidor() throws IOException {
        try {
            return adaptador.listenUsingRfcommWithServiceRecord(Protocolo.NOMBRE_SERVICIO, Protocolo.UUID_SERVICIO);
        } catch (IOException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
                return adaptador.listenUsingInsecureRfcommWithServiceRecord(
                        Protocolo.NOMBRE_SERVICIO, Protocolo.UUID_SERVICIO);
            }
            throw e;
        }
    }

    private void atender(final BluetoothSocket socket) throws IOException {
        oyente.conectado(nombreDe(socket.getRemoteDevice()));
        SesionReceptor s = new SesionReceptor(socket.getInputStream(), socket.getOutputStream(), hello, cola,
                reproductor, new SesionReceptor.Oyente() {
                    @Override
                    public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
                        oyente.pruebaTerminada(bytesRecibidos, duracionMs);
                    }

                    @Override
                    public void track(Track track) {
                        oyente.track(track);
                    }
                }, cl.parlantey.core.Reloj.SISTEMA);
        HiloEstado estado = new HiloEstado(s, socket);
        HiloMicrofono microfono = BuildConfig.MICROFONO ? new HiloMicrofono(s.escritor()) : null;
        try {
            s.iniciar();
            sesion = s;
            if (despidiendo) {
                s.despedir(); // "Detener receptor" llegó mientras se aceptaba esta conexión
            }
            estado.start();
            if (microfono != null) {
                microfono.start(); // después del HELLO: MIC_CONFIG no puede ir primero
            }
            int codigo;
            do {
                codigo = s.procesarTrama();
            } while (codigo < 0 && activo);
            if (codigo == Protocolo.BYE_VERSION) {
                oyente.versionIncompatible();
            } else if (codigo >= 0) {
                oyente.desconectado(R.string.evento_emisor_salio, null);
            }
        } catch (ProtocoloException e) {
            oyente.desconectado(R.string.evento_protocolo, e.getMessage());
        } catch (IOException e) {
            if (activo) {
                oyente.desconectado(estado.vencido ? R.string.evento_sin_respuesta : R.string.evento_conexion_perdida,
                        null);
            }
        } finally {
            sesion = null;
            estado.detener();
            if (microfono != null) {
                microfono.detener();
            }
            s.terminar(); // como DETENER: la reproducción se detiene y la salida se libera
        }
    }

    /** Hilo de STATE de una conexión: STATE cada 250 ms y watchdog de 5 s sin tramas. */
    private final class HiloEstado extends Thread {
        private final SesionReceptor sesion;
        private final BluetoothSocket socket;
        private volatile boolean sigue = true;
        volatile boolean vencido;

        HiloEstado(SesionReceptor sesion, BluetoothSocket socket) {
            super("ParlanteY-estado");
            this.sesion = sesion;
            this.socket = socket;
        }

        void detener() {
            sigue = false;
            interrupt();
        }

        @Override
        public void run() {
            try {
                while (sigue) {
                    if (!sesion.tickEstado(System.nanoTime())) {
                        Log.i(Registro.TAG, "5 s sin tramas del emisor: se cierra la conexión");
                        vencido = true;
                        break;
                    }
                    long ms = sesion.nanosHastaEstado(System.nanoTime()) / 1000000L;
                    Thread.sleep(Math.max(1, ms));
                }
            } catch (InterruptedException fin) {
                return; // detener()
            } catch (IOException e) {
                // La conexión se cortó: el hilo de red se entera al leer.
            } catch (Throwable t) {
                oyente.error("STATE", t);
            }
            if (sigue) {
                cerrar(socket); // desbloquea la lectura del hilo de red
            }
        }
    }

    private static String nombreDe(BluetoothDevice dispositivo) {
        String nombre = dispositivo.getName();
        return nombre != null ? nombre : dispositivo.getAddress();
    }

    private static void cerrar(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignorada) {
                // Nada que hacer: se está cerrando.
            }
        }
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // detener() interrumpe; el bucle revisa "activo".
        }
    }
}
