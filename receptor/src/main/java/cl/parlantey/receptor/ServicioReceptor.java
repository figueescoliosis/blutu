package cl.parlantey.receptor;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.widget.RemoteViews;

import cl.parlantey.core.ColaBloques;
import cl.parlantey.core.Config;
import cl.parlantey.core.Hello;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.Reproductor;
import cl.parlantey.core.Track;

/**
 * Servicio en primer plano del receptor: dueño de la cola de bloques, el reproductor, el hilo de
 * audio (prioridad URGENT_AUDIO) y el servidor RFCOMM. Mientras hay una conexión mantiene un
 * PARTIAL_WAKE_LOCK, así que sigue sonando con la pantalla apagada. La actividad solo lo arranca,
 * lee su estado ({@link #instancia}) y le pide detenerse.
 */
public final class ServicioReceptor extends Service implements HiloServidor.Oyente {

    static final String ACCION_DETENER = "cl.parlantey.receptor.DETENER";

    /** Pool para 4000 ms en el peor caso: 48 kHz con bloques de 1024 = 188 bloques. */
    static final int CAPACIDAD_BUFFER_MS = 4000;
    static final int POOL_BLOQUES = 188;
    /** Bloque estéreo de 1024 muestras: 2 × (4 + 512). */
    static final int BYTES_BLOQUE = 1032;
    private static final int MAX_CANALES = 2;
    private static final int ID_NOTIFICACION = 1;
    private static final long INTERVALO_METRICAS_MS = 60000;

    static final int BT_APAGADO = 0;
    static final int ESPERANDO = 1;
    static final int CONECTADO = 2;
    static final int DETENIDO = 3;

    /** El servicio vivo, o null. Lo lee la actividad. */
    static volatile ServicioReceptor instancia;

    private final Handler principal = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adaptador;
    private ColaBloques cola;
    private SalidaAudioTrack salida;
    private Reproductor reproductor;
    private HiloAudio hiloAudio;
    private HiloServidor servidor;
    private PowerManager.WakeLock wakeLock;
    private boolean receptorRegistrado;
    /** "when" de la notificación: fijo, para que no salte de lugar al actualizarse. */
    private long inicio;

    // --- Estado para la UI (escrito desde el hilo de red o el principal).
    volatile int estadoConexion = ESPERANDO;
    volatile String emisor = "";
    volatile Track track;
    volatile String evento = "";
    volatile String prueba = "";
    volatile String nombreLocal = "";

    // --- Métricas de cada minuto (hilo principal).
    private long cpuAnteriorMs;
    private int gcAnterior;
    volatile int gcPorMinuto = -1;
    volatile int cpuPorciento = -1;
    private int cortesAnteriores;

    private final BroadcastReceiver cambiosBluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int nuevo = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (nuevo == BluetoothAdapter.STATE_ON) {
                iniciarServidor();
            } else if (nuevo == BluetoothAdapter.STATE_TURNING_OFF || nuevo == BluetoothAdapter.STATE_OFF) {
                detenerServidor();
                bluetoothApagado();
            }
        }
    };

    private final Runnable metricas = new Runnable() {
        @Override
        public void run() {
            registrarMetricas();
            principal.postDelayed(this, INTERVALO_METRICAS_MS);
        }
    };

    @Override
    @SuppressWarnings("deprecation") // Debug.*AllocCounting y getGlobalGcInvocationCount: los de 2.3
    public void onCreate() {
        super.onCreate();
        Registro.instalarManejador(this);
        inicio = System.currentTimeMillis();
        startForeground(ID_NOTIFICACION, notificacion(getString(R.string.estado_esperando), ""));

        cola = new ColaBloques(POOL_BLOQUES, BYTES_BLOQUE);
        salida = new SalidaAudioTrack();
        reproductor = new Reproductor(cola, salida, CAPACIDAD_BUFFER_MS);
        hiloAudio = new HiloAudio();
        hiloAudio.start();

        PowerManager energia = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = energia.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParlanteY:receptor");
        wakeLock.setReferenceCounted(false);

        Debug.startAllocCounting(); // para contar las recolecciones de basura (GC) por minuto
        cpuAnteriorMs = Process.getElapsedCpuTime();
        gcAnterior = Debug.getGlobalGcInvocationCount();
        Log.i(Registro.TAG, "receptor " + BuildConfig.VERSION_NAME + " en API " + Build.VERSION.SDK_INT + " ("
                + Build.MODEL + "); " + SalidaAudioTrack.diagnostico());
        principal.postDelayed(metricas, INTERVALO_METRICAS_MS);
        instancia = this;

        adaptador = adaptadorBluetooth();
        if (adaptador == null) {
            estadoConexion = BT_APAGADO;
            return;
        }
        registerReceiver(cambiosBluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        receptorRegistrado = true;
        if (adaptador.isEnabled()) {
            iniciarServidor();
        } else {
            bluetoothApagado();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // El sistema recreó el servicio tras matar el proceso (START_STICKY).
            Registro.contarReinicio(this);
        } else if (ACCION_DETENER.equals(intent.getAction())) {
            detenerReceptor();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDestroy() {
        instancia = null;
        principal.removeCallbacks(metricas);
        if (receptorRegistrado) {
            unregisterReceiver(cambiosBluetooth);
            receptorRegistrado = false;
        }
        detenerServidor();
        hiloAudio.detener();
        Debug.stopAllocCounting();
        soltarWakeLock();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** BluetoothManager (API 18) no existe en 2.3: getDefaultAdapter() es la única vía. */
    @SuppressWarnings("deprecation")
    private static BluetoothAdapter adaptadorBluetooth() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    private void iniciarServidor() {
        if (servidor != null && servidor.isAlive()) {
            return;
        }
        String nombre = adaptador.getName();
        if (nombre == null) {
            nombre = Build.MODEL;
        }
        nombreLocal = nombre + " (" + adaptador.getAddress() + ")";
        Hello hello = new Hello(Protocolo.VERSION, Build.VERSION.SDK_INT, nombre, MAX_CANALES, CAPACIDAD_BUFFER_MS);
        servidor = new HiloServidor(adaptador, hello, cola, reproductor, this);
        servidor.start();
    }

    private void detenerServidor() {
        if (servidor != null) {
            servidor.detener();
            servidor = null;
        }
    }

    /** "Detener receptor": BYE(0) al emisor (fuera del hilo principal) y fin del servicio. */
    private void detenerReceptor() {
        estadoConexion = DETENIDO;
        final HiloServidor s = servidor;
        servidor = null;
        if (s != null) {
            new Thread("ParlanteY-despedida") {
                @Override
                public void run() {
                    s.despedirYDetener();
                }
            }.start();
        }
        stopSelf();
    }

    // --- Lecturas para la UI.

    Reproductor reproductor() {
        return reproductor;
    }

    ColaBloques cola() {
        return cola;
    }

    /** bufferMs como en STATE (con el monitor de la cola, igual que el hilo de STATE). */
    long bufferMs() {
        synchronized (cola) {
            return reproductor.bufferMs();
        }
    }

    int minBufferBytes() {
        return salida.minBufferBytes;
    }

    int capacidadSalidaFrames() {
        return salida.capacidadFramesPublicada;
    }

    int prioridadAudio() {
        return hiloAudio.prioridadEfectiva;
    }

    // --- HiloServidor.Oyente (hilo de red).

    @Override
    public void esperando() {
        estadoConexion = ESPERANDO;
        emisor = "";
        track = null;
        soltarWakeLock();
        actualizarNotificacion(getString(R.string.estado_esperando), "");
    }

    @Override
    @SuppressLint("WakelockTimeout") // se suelta al terminar la conexión, que puede durar horas
    public void conectado(String nombre) {
        estadoConexion = CONECTADO;
        emisor = nombre;
        track = null;
        evento = "";
        wakeLock.acquire();
        Log.i(Registro.TAG, "conectado a " + nombre);
        actualizarNotificacion(getString(R.string.estado_conectado, nombre), "");
    }

    @Override
    public void desconectado(int mensaje, String detalle) {
        evento = detalle == null ? getString(mensaje) : getString(mensaje, detalle);
        Log.i(Registro.TAG, "desconectado: " + evento);
        soltarWakeLock();
    }

    @Override
    public void pruebaTerminada(long bytesRecibidos, long duracionMs) {
        // bytes / ms = KB/s con KB = 1000 bytes.
        long kBpsx10 = bytesRecibidos * 10 / Math.max(1L, duracionMs);
        prueba = getString(R.string.prueba_resultado, Formato.decimal(kBpsx10), Formato.decimal(bytesRecibidos / 100),
                Formato.decimal(duracionMs / 100));
    }

    @Override
    public void track(Track t) {
        track = t;
        actualizarNotificacion(getString(R.string.estado_conectado, emisor), Formato.track(t));
    }

    @Override
    public void versionIncompatible() {
        evento = getString(R.string.evento_version);
    }

    @Override
    public void bluetoothApagado() {
        estadoConexion = BT_APAGADO;
        soltarWakeLock();
        actualizarNotificacion(getString(R.string.estado_bt_apagado), "");
    }

    @Override
    public void error(String donde, Throwable t) {
        Registro.guardarError(this, donde, t);
    }

    private void soltarWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // --- Notificación (Android 2.3: new Notification() con RemoteViews propio).

    private void actualizarNotificacion(final String linea1, final String linea2) {
        principal.post(new Runnable() {
            @Override
            public void run() {
                if (instancia != ServicioReceptor.this) {
                    return; // el servicio ya terminó: no resucitar la notificación
                }
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(ID_NOTIFICACION, notificacion(linea1, linea2));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private Notification notificacion(String linea1, String linea2) {
        Notification n = new Notification();
        n.icon = R.drawable.ic_notificacion;
        n.tickerText = getString(R.string.app_name);
        n.when = inicio;
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        RemoteViews vista = new RemoteViews(getPackageName(), R.layout.notificacion);
        vista.setTextViewText(R.id.notificacion_linea1, linea1);
        vista.setTextViewText(R.id.notificacion_linea2,
                linea2.length() > 0 ? linea2 : getString(R.string.notificacion_toca));
        n.contentView = vista;
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        n.contentIntent = PendingIntent.getActivity(this, 0, abrir, 0);
        return n;
    }

    // --- Métricas para NOTAS.md (logcat -s ParlanteY).

    @SuppressWarnings("deprecation")
    private void registrarMetricas() {
        long cpu = Process.getElapsedCpuTime();
        int gc = Debug.getGlobalGcInvocationCount();
        cpuPorciento = (int) ((cpu - cpuAnteriorMs) * 100 / INTERVALO_METRICAS_MS);
        gcPorMinuto = gc - gcAnterior;
        cpuAnteriorMs = cpu;
        gcAnterior = gc;
        if (estadoConexion != CONECTADO) {
            return;
        }
        Reproductor r = reproductor;
        Config c = r.config();
        int cortes = r.cortes();
        Log.i(Registro.TAG, "minuto: " + (c == null ? "sin audio" : c.sampleRate + " Hz × " + c.canales)
                + ", buffer " + bufferMs() + " ms, cortes " + cortes + " (+" + (cortes - cortesAnteriores)
                + "), subcortes " + r.subcortes() + ", mín. pendiente " + r.minPendienteFrames()
                + " frames, decodificación " + r.decodPromedioUs() + "/" + r.decodMaximoUs()
                + " µs (prom./máx.), intervalo máx. de escritura " + r.intervaloMaximoMs() + " ms, desbordes "
                + cola.desbordes() + ", errores de salida " + r.erroresSalida() + ", GC " + gcPorMinuto
                + "/min, CPU " + cpuPorciento + " %, prioridad del audio " + hiloAudio.prioridadEfectiva
                + ", getMinBufferSize " + salida.minBufferBytes + " B");
        cortesAnteriores = cortes;
    }

    /** Hilo de audio: da pasos al reproductor y espera en la cola entre uno y otro. */
    private final class HiloAudio extends Thread {
        private volatile boolean activo = true;
        volatile int prioridadEfectiva = Integer.MIN_VALUE;

        HiloAudio() {
            super("ParlanteY-audio");
        }

        void detener() {
            activo = false;
            cola.despertar();
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            prioridadEfectiva = Process.getThreadPriority(Process.myTid());
            Log.i(Registro.TAG, "hilo de audio con prioridad " + prioridadEfectiva + " (pedida "
                    + Process.THREAD_PRIORITY_URGENT_AUDIO + ")");
            while (activo) {
                try {
                    long espera = reproductor.paso(System.nanoTime());
                    cola.esperar(espera);
                } catch (Throwable t) {
                    Registro.guardarError(ServicioReceptor.this, "audio", t);
                    cola.esperar(100);
                }
            }
            reproductor.cerrar();
        }
    }
}
