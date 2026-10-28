package cl.parlantey.pc;

import cl.parlantey.core.ColaBloques;
import cl.parlantey.core.ImaAdpcm;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.Reproductor;
import cl.parlantey.core.Salida;
import com.sun.jna.Function;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Ole32;

/**
 * El micrófono del Galaxy Y (app ParlanteY Mic, protocolo v4) en el PC: lo mismo que hace el
 * receptor con la música, al revés. {@link cl.parlantey.core.SesionEmisor} deja MIC_CONFIG y MIC_AUDIO
 * en {@link #cola()}; un hilo de audio los suena con un {@link Reproductor} en vivo (compensa la
 * deriva de reloj) en «CABLE Input» ({@link SalidaWasapi}).
 */
final class MicrofonoPc {

    /** ~1 s de bloques de 16 ms: sobra (el Reproductor descarta lo atrasado). */
    static final int BLOQUES = 64;

    private final ColaBloques cola = new ColaBloques(BLOQUES, ImaAdpcm.bytesBloque(1, Protocolo.MUESTRAS_MIC));
    private final SalidaWasapi wasapi;
    private final Reproductor reproductor;
    private volatile boolean activo;
    private Thread hilo;

    MicrofonoPc() {
        this(new SalidaWasapi());
    }

    /** Para los tests: otra salida (sin COM). */
    MicrofonoPc(Salida salida) {
        this.wasapi = salida instanceof SalidaWasapi ? (SalidaWasapi) salida : null;
        this.reproductor = new Reproductor(cola, salida, BLOQUES * 16L);
    }

    ColaBloques cola() {
        return cola;
    }

    Reproductor reproductor() {
        return reproductor;
    }

    /** Error de la salida (p. ej. falta VB-Cable), o null. */
    String error() {
        return wasapi == null ? null : wasapi.error;
    }

    /** Nombre del dispositivo donde suena, o null. */
    String dispositivo() {
        return wasapi == null ? null : wasapi.dispositivo;
    }

    synchronized void iniciar() {
        if (hilo != null) {
            return;
        }
        activo = true;
        hilo = new Thread(this::ejecutar, "ParlanteY-microfono");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MAX_PRIORITY);
        hilo.start();
    }

    /** Fin de una conexión: deja de sonar y suelta la salida (como DETENER en el receptor). */
    void finConexion() {
        cola.nuevaGeneracion(null);
    }

    synchronized void detener() {
        activo = false;
        cola.despertar();
        if (hilo != null) {
            try {
                hilo.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            hilo = null;
        }
    }

    private void ejecutar() {
        boolean com = wasapi != null && Platform.isWindows();
        if (com) {
            Ole32.INSTANCE.CoInitializeEx(null, CapturaWindows.COINIT_MULTITHREADED);
            // Timer de 1 ms: con el de 15,6 ms por defecto, un buffer de 30 ms se vaciaría entre pasos.
            Function.getFunction("winmm", "timeBeginPeriod").invokeInt(new Object[] {1});
        }
        try {
            while (activo) {
                try {
                    long eventos = cola.eventos();
                    long espera = reproductor.paso(System.nanoTime());
                    cola.esperarTras(eventos, espera);
                } catch (RuntimeException e) {
                    cola.esperar(100);
                }
            }
        } finally {
            reproductor.cerrar();
            if (com) {
                Function.getFunction("winmm", "timeEndPeriod").invokeInt(new Object[] {1});
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }
}
