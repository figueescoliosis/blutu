package cl.parlantey.receptor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import cl.parlantey.core.Config;
import cl.parlantey.core.Preset;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.Reproductor;
import cl.parlantey.core.Track;

/**
 * Pantalla única del receptor (240×320). Arranca {@link ServicioReceptor}, que hace todo el
 * trabajo, y muestra su estado cada 250 ms mientras está visible (solo actualiza lo que cambió).
 */
public class MainActivity extends Activity {

    private static final int PEDIR_ENCENDER = 1;
    private static final int PEDIR_VISIBLE = 2;
    private static final int DURACION_VISIBLE_S = 300;
    private static final long REFRESCO_MS = 250;
    private static final long EVENTO_LOCAL_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adaptador;

    private TextView estado;
    private TextView pista;
    private ProgressBar barra;
    private TextView buffer;
    private TextView formato;
    private TextView evento;
    private TextView diagnostico;
    private TextView prueba;
    private TextView esteTelefono;
    /** Solo en la app Mic. */
    private TextView mic;
    private Button botonMic;
    private AudioManager audio;

    /** Mensaje de la actividad (visibilidad, Bluetooth rechazado); tapa al del servicio. */
    private String eventoLocal = "";
    /** SystemClock.elapsedRealtime en que el mensaje local deja de mostrarse. */
    private long eventoLocalHastaMs;
    /** Último mensaje del servicio visto: uno nuevo reemplaza al mensaje local. */
    private String eventoServicioVisto = "";
    /** Se leen del disco una vez por onResume, no en cada refresco. */
    private String errorGuardado = "";
    private int reinicios;
    private String etiquetaBuffer;
    private String etiquetaCortes;

    // Lo último mostrado, para no rehacer textos si nada cambió.
    private int vEstado = -1;
    private String vEmisor;
    private int vEstadoReproductor = -1;
    private boolean vReproduciendo;
    private boolean vFinalizado;
    private long vDeriva;
    private long vSalidaMs;
    private Track vTrack;
    private long vBufferMs = -1;
    private int vCortes = -1;
    private Config vConfig;
    private long vDecodUs = -1;
    private String vEvento;
    private long vDiagnostico = -1;
    private String vPrueba;
    private String vNombre;
    private int vMic = -1;

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            refrescar();
            ui.postDelayed(this, REFRESCO_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.principal);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        estado = (TextView) findViewById(R.id.estado);
        pista = (TextView) findViewById(R.id.pista);
        barra = (ProgressBar) findViewById(R.id.barra_buffer);
        barra.setMax(ServicioReceptor.CAPACIDAD_BUFFER_MS);
        buffer = (TextView) findViewById(R.id.buffer);
        formato = (TextView) findViewById(R.id.formato);
        evento = (TextView) findViewById(R.id.evento);
        diagnostico = (TextView) findViewById(R.id.diagnostico);
        prueba = (TextView) findViewById(R.id.prueba);
        esteTelefono = (TextView) findViewById(R.id.este_telefono);
        etiquetaBuffer = getString(R.string.buffer_etiqueta);
        etiquetaCortes = getString(R.string.buffer_cortes);
        ((TextView) findViewById(R.id.version)).setText(
                getString(R.string.version, BuildConfig.VERSION_NAME, Protocolo.VERSION));

        if (BuildConfig.MICROFONO) {
            findViewById(R.id.fila_mic).setVisibility(View.VISIBLE);
            mic = (TextView) findViewById(R.id.mic);
            botonMic = (Button) findViewById(R.id.boton_mic);
            audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            botonMic.setOnClickListener(v -> {
                HiloMicrofono.enviar = !HiloMicrofono.enviar;
                vMic = -1;
                refrescar();
            });
        }

        Button visible = (Button) findViewById(R.id.boton_visible);
        Button detener = (Button) findViewById(R.id.boton_detener);
        detener.setOnClickListener(v -> detenerReceptor());
        adaptador = adaptadorBluetooth();
        if (adaptador == null) {
            estado.setText(R.string.estado_sin_bt);
            visible.setEnabled(false);
            return;
        }
        visible.setOnClickListener(v -> hacerVisible());

        startService(new Intent(this, ServicioReceptor.class));
        if (!adaptador.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), PEDIR_ENCENDER);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        errorGuardado = Registro.ultimoError(this);
        reinicios = Registro.reinicios(this);
        vDiagnostico = -1;
        ui.post(refresco);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresco);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PEDIR_ENCENDER && resultCode != RESULT_OK) {
            mostrarLocal(getString(R.string.evento_bt_rechazado), EVENTO_LOCAL_MS);
        } else if (requestCode == PEDIR_VISIBLE) {
            // Con ACTION_REQUEST_DISCOVERABLE, resultCode es la duración en segundos.
            if (resultCode == RESULT_CANCELED) {
                mostrarLocal(getString(R.string.evento_visible_cancelado), EVENTO_LOCAL_MS);
            } else {
                mostrarLocal(getString(R.string.evento_visible, resultCode), resultCode * 1000L);
            }
        }
    }

    /** Mensaje de la pantalla por {@code ms}; un mensaje nuevo del servicio lo reemplaza antes. */
    private void mostrarLocal(String mensaje, long ms) {
        eventoLocal = mensaje;
        eventoLocalHastaMs = SystemClock.elapsedRealtime() + ms;
    }

    /** BluetoothManager (API 18) no existe en 2.3: getDefaultAdapter() es la única vía. */
    @SuppressWarnings("deprecation")
    private static BluetoothAdapter adaptadorBluetooth() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    private void hacerVisible() {
        // Si el Bluetooth está apagado, este mismo diálogo lo enciende.
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DURACION_VISIBLE_S);
        startActivityForResult(intent, PEDIR_VISIBLE);
    }

    /** BYE(0) al emisor (que no reintenta), fin del servicio y de la app. */
    private void detenerReceptor() {
        Intent intent = new Intent(this, ServicioReceptor.class);
        intent.setAction(ServicioReceptor.ACCION_DETENER);
        startService(intent);
        finish();
    }

    @SuppressLint("SetTextI18n") // la línea del buffer se arma sin String.format (4 veces por segundo)
    private void refrescar() {
        ServicioReceptor s = ServicioReceptor.instancia;
        if (s == null) {
            if (vEstado != ServicioReceptor.DETENIDO) {
                vEstado = ServicioReceptor.DETENIDO;
                estado.setText(R.string.estado_iniciando);
            }
            return;
        }
        Reproductor r = s.reproductor();

        int est = s.estadoConexion;
        String emisor = s.emisor;
        int estRep = r.estado();
        boolean sonando = r.reproduciendo();
        boolean finalizado = r.finalizado();
        if (est != vEstado || !emisor.equals(vEmisor) || estRep != vEstadoReproductor || sonando != vReproduciendo
                || finalizado != vFinalizado) {
            vEstado = est;
            vEmisor = emisor;
            vEstadoReproductor = estRep;
            vReproduciendo = sonando;
            vFinalizado = finalizado;
            estado.setText(textoEstado(est, emisor, estRep, sonando, r.pausado(), finalizado));
        }

        Track t = s.track;
        if (t != vTrack) {
            vTrack = t;
            pista.setText(t == null ? getString(R.string.pista_ninguna) : Formato.track(t));
        }

        long b = s.bufferMs();
        int cortes = r.cortes();
        if (b / 10 != vBufferMs / 10 || cortes != vCortes) {
            vBufferMs = b;
            vCortes = cortes;
            barra.setProgress((int) b);
            // Cambia 4 veces por segundo: concatenación simple, sin String.format.
            buffer.setText(etiquetaBuffer + b + " ms · " + cortes + " " + etiquetaCortes);
        }

        Config c = r.config();
        long decod = r.decodPromedioUs();
        long deriva = r.framesDuplicados() - r.framesDescartados() - r.bloquesDescartados() * 1024;
        // Buffer real del AudioTrack (2 × getMinBufferSize): el piso de la latencia.
        long salidaMs = c == null ? 0 : s.capacidadSalidaFrames() * 1000L / c.sampleRate;
        if (c != vConfig || decod / 100 != vDecodUs / 100 || deriva != vDeriva || salidaMs != vSalidaMs) {
            vConfig = c;
            vDecodUs = decod;
            vDeriva = deriva;
            vSalidaMs = salidaMs;
            String texto = c == null ? getString(R.string.formato_ninguno) : textoFormato(c, decod);
            if (c != null && c.enVivo) {
                // En vivo: frames que la compensación de deriva agregó (+) o quitó (−).
                texto = texto + " · " + getString(R.string.en_vivo) + " " + (deriva > 0 ? "+" : "") + deriva;
            }
            if (salidaMs > 0) {
                texto = texto + "\n" + getString(R.string.salida_audio, salidaMs);
            }
            formato.setText(texto);
        }

        String delServicio = s.evento;
        if (!delServicio.equals(eventoServicioVisto)) {
            eventoServicioVisto = delServicio;
            if (delServicio.length() > 0) {
                eventoLocal = ""; // un error de conexión o de versión no debe quedar tapado
            }
        }
        if (eventoLocal.length() > 0 && SystemClock.elapsedRealtime() > eventoLocalHastaMs) {
            eventoLocal = "";
        }
        String ev = eventoLocal.length() > 0 ? eventoLocal : delServicio;
        if (!ev.equals(vEvento)) {
            vEvento = ev;
            evento.setText(ev.length() == 0 ? getString(R.string.evento_ninguno) : ev);
        }

        int sub = r.subcortes();
        int desb = s.cola().desbordes();
        int err = r.erroresSalida();
        long clave = ((long) sub << 40) ^ ((long) desb << 30) ^ ((long) err << 20) ^ ((long) s.gcPorMinuto << 10)
                ^ ((long) s.cpuPorciento << 5) ^ s.prioridadAudio();
        if (clave != vDiagnostico) {
            vDiagnostico = clave;
            diagnostico.setText(textoDiagnostico(s, sub, desb, err));
        }

        String pr = s.prueba;
        if (!pr.equals(vPrueba)) {
            vPrueba = pr;
            prueba.setText(pr.length() == 0 ? getString(R.string.prueba_ninguna) : pr);
        }

        if (BuildConfig.MICROFONO) {
            refrescarMic();
        }

        String nombre = s.nombreLocal;
        if (!nombre.equals(vNombre)) {
            vNombre = nombre;
            esteTelefono.setText(getString(R.string.este_telefono, nombre));
        }
    }

    /** «Mic (audífono): enviando · nivel 40 %»; el nivel, de a 10 %. */
    @SuppressWarnings("deprecation") // isWiredHeadsetOn: la única vía en 2.3
    private void refrescarMic() {
        boolean audifono = audio.isWiredHeadsetOn();
        boolean enviar = HiloMicrofono.enviar;
        int est = HiloMicrofono.estado;
        int nivel = HiloMicrofono.pico * 10 / 32768 * 10;
        int clave = (audifono ? 1 : 0) | (enviar ? 2 : 0) | est << 2 | nivel << 4;
        if (clave == vMic) {
            return;
        }
        vMic = clave;
        int texto = !enviar ? R.string.mic_apagado
                : est == HiloMicrofono.ENVIANDO ? R.string.mic_enviando
                : est == HiloMicrofono.ERROR ? R.string.mic_error
                : R.string.mic_esperando;
        mic.setText(getString(R.string.mic, getString(audifono ? R.string.mic_audifono : R.string.mic_telefono),
                getString(texto), nivel));
        botonMic.setText(enviar ? R.string.boton_mic_on : R.string.boton_mic_off);
    }

    private String textoEstado(int est, String emisor, int estRep, boolean sonando, boolean pausado,
            boolean finalizado) {
        switch (est) {
            case ServicioReceptor.BT_APAGADO:
                return getString(R.string.estado_bt_apagado);
            case ServicioReceptor.ESPERANDO:
                return getString(R.string.estado_esperando);
            case ServicioReceptor.CONECTADO:
                if (pausado && estRep != Reproductor.SIN_CONFIG) {
                    return getString(R.string.estado_en_pausa, emisor);
                }
                if (sonando) {
                    return getString(R.string.estado_reproduciendo, emisor);
                }
                if (finalizado) {
                    return getString(R.string.estado_fin_cola, emisor);
                }
                if (estRep == Reproductor.LLENANDO) {
                    return getString(R.string.estado_llenando, emisor);
                }
                if (estRep == Reproductor.RELLENANDO) {
                    return getString(R.string.estado_corte, emisor);
                }
                return getString(R.string.estado_conectado, emisor);
            default:
                return getString(R.string.estado_detenido);
        }
    }

    /** «48 kHz estéreo · Alta · dec 1,2 ms» */
    private String textoFormato(Config c, long decodUs) {
        String tasa = c.sampleRate % 1000 == 0
                ? String.valueOf(c.sampleRate / 1000)
                : Formato.decimal(c.sampleRate / 100);
        String canales = getString(c.canales == 2 ? R.string.estereo : R.string.mono);
        return getString(R.string.formato, tasa, canales, Preset.deducir(c.canales, c.sampleRate).nombre,
                Formato.decimal(decodUs / 100));
    }

    private String textoDiagnostico(ServicioReceptor s, int subcortes, int desbordes, int errores) {
        String base = getString(R.string.diagnostico, subcortes, desbordes, errores,
                s.gcPorMinuto < 0 ? "–" : String.valueOf(s.gcPorMinuto),
                s.cpuPorciento < 0 ? "–" : String.valueOf(s.cpuPorciento),
                s.prioridadAudio() == Integer.MIN_VALUE ? "–" : String.valueOf(s.prioridadAudio()),
                reinicios);
        return errorGuardado.length() == 0 ? base : base + "\n" + getString(R.string.ultimo_error, errorGuardado);
    }
}
