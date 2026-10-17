package cl.parlantey.receptor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import java.io.Closeable;
import java.io.IOException;

import cl.parlantey.core.Hello;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.ProtocoloException;
import cl.parlantey.core.SesionReceptor;

/**
 * Servidor RFCOMM: publica el servicio, acepta una conexión a la vez y la atiende con
 * {@link SesionReceptor}. Al perderse la conexión vuelve a {@code accept()} solo.
 */
final class HiloServidor extends Thread {

    /** Avisos a la UI. Se llaman desde este hilo. */
    interface Oyente {
        void esperando();

        void conectado(String nombre);

        void desconectado(int mensaje, String detalle);

        void pruebaTerminada(long bytesRecibidos, long duracionMs);

        void versionIncompatible();

        void bluetoothApagado();
    }

    private static final long ESPERA_MAX_MS = 10000;

    private final BluetoothAdapter adaptador;
    private final Hello hello;
    private final Oyente oyente;

    private volatile boolean activo = true;
    private volatile Closeable abierto;

    HiloServidor(BluetoothAdapter adaptador, Hello hello, Oyente oyente) {
        super("ParlanteY-servidor");
        this.adaptador = adaptador;
        this.hello = hello;
        this.oyente = oyente;
    }

    /** Detiene el hilo. Cerrar el socket desbloquea {@code accept()} o la lectura en curso. */
    void detener() {
        activo = false;
        cerrar(abierto);
        interrupt();
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
                atender(socket);
            }
            cerrar(socket);
            abierto = null;
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

    private void atender(BluetoothSocket socket) {
        oyente.conectado(nombreDe(socket.getRemoteDevice()));
        try {
            int codigo = SesionReceptor.atender(socket.getInputStream(), socket.getOutputStream(), hello,
                    oyente::pruebaTerminada);
            if (codigo == Protocolo.BYE_VERSION) {
                oyente.versionIncompatible();
            } else {
                oyente.desconectado(R.string.evento_emisor_salio, null);
            }
        } catch (ProtocoloException e) {
            oyente.desconectado(R.string.evento_protocolo, e.getMessage());
        } catch (IOException e) {
            if (activo) {
                oyente.desconectado(R.string.evento_conexion_perdida, null);
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
