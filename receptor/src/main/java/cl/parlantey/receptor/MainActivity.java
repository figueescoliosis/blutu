package cl.parlantey.receptor;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

import cl.parlantey.core.Hello;
import cl.parlantey.core.Protocolo;

/**
 * Fase 0: pantalla única del receptor. Mantiene un {@link HiloServidor} mientras la
 * actividad existe; el servicio en primer plano llega en la Fase 2.
 */
public class MainActivity extends Activity implements HiloServidor.Oyente {

    private static final int PEDIR_ENCENDER = 1;
    private static final int PEDIR_VISIBLE = 2;
    private static final int DURACION_VISIBLE_S = 300;
    private static final int MAX_CANALES = 2;
    private static final int CAPACIDAD_BUFFER_MS = 4000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adaptador;
    private HiloServidor hilo;
    private boolean receptorRegistrado;

    private TextView estado;
    private TextView evento;
    private TextView prueba;
    private TextView esteTelefono;

    private final BroadcastReceiver cambiosBluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int nuevo = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (nuevo == BluetoothAdapter.STATE_ON) {
                iniciarServidor();
            } else if (nuevo == BluetoothAdapter.STATE_TURNING_OFF || nuevo == BluetoothAdapter.STATE_OFF) {
                detenerServidor();
                estado.setText(R.string.estado_bt_apagado);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.principal);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        estado = (TextView) findViewById(R.id.estado);
        evento = (TextView) findViewById(R.id.evento);
        prueba = (TextView) findViewById(R.id.prueba);
        esteTelefono = (TextView) findViewById(R.id.este_telefono);
        ((TextView) findViewById(R.id.version)).setText(
                getString(R.string.version, BuildConfig.VERSION_NAME, Protocolo.VERSION));

        Button visible = (Button) findViewById(R.id.boton_visible);
        adaptador = adaptadorBluetooth();
        if (adaptador == null) {
            estado.setText(R.string.estado_sin_bt);
            visible.setEnabled(false);
            return;
        }
        visible.setOnClickListener(v -> hacerVisible());

        registerReceiver(cambiosBluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        receptorRegistrado = true;

        if (adaptador.isEnabled()) {
            iniciarServidor();
        } else {
            estado.setText(R.string.estado_bt_apagado);
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), PEDIR_ENCENDER);
        }
    }

    @Override
    protected void onDestroy() {
        if (receptorRegistrado) {
            unregisterReceiver(cambiosBluetooth);
        }
        detenerServidor();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PEDIR_ENCENDER && resultCode != RESULT_OK) {
            evento.setText(R.string.evento_bt_rechazado);
        } else if (requestCode == PEDIR_VISIBLE) {
            // Con ACTION_REQUEST_DISCOVERABLE, resultCode es la duración en segundos.
            if (resultCode == RESULT_CANCELED) {
                evento.setText(R.string.evento_visible_cancelado);
            } else {
                evento.setText(getString(R.string.evento_visible, resultCode));
            }
        }
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

    private void iniciarServidor() {
        if (hilo != null && hilo.isAlive()) {
            return;
        }
        String nombre = adaptador.getName();
        if (nombre == null) {
            nombre = Build.MODEL;
        }
        esteTelefono.setText(getString(R.string.este_telefono, nombre + " (" + adaptador.getAddress() + ")"));
        Hello hello = new Hello(Protocolo.VERSION, Build.VERSION.SDK_INT, nombre, MAX_CANALES, CAPACIDAD_BUFFER_MS);
        hilo = new HiloServidor(adaptador, hello, this);
        hilo.start();
    }

    private void detenerServidor() {
        if (hilo != null) {
            hilo.detener();
            hilo = null;
        }
    }

    // --- HiloServidor.Oyente: llegan desde el hilo del servidor y se pasan al hilo de la UI.

    @Override
    public void esperando() {
        ui.post(() -> estado.setText(R.string.estado_esperando));
    }

    @Override
    public void conectado(final String nombre) {
        ui.post(() -> {
            estado.setText(getString(R.string.estado_conectado, nombre));
            evento.setText(R.string.evento_ninguno);
        });
    }

    @Override
    public void desconectado(final int mensaje, final String detalle) {
        ui.post(() -> evento.setText(detalle == null ? getString(mensaje) : getString(mensaje, detalle)));
    }

    @Override
    public void pruebaTerminada(final long bytesRecibidos, final long duracionMs) {
        ui.post(() -> {
            // bytes / ms = KB/s con KB = 1000 bytes.
            double kBps = bytesRecibidos / (double) Math.max(1L, duracionMs);
            prueba.setText(getString(R.string.prueba_resultado,
                    decimal(kBps), decimal(bytesRecibidos / 1000.0), decimal(duracionMs / 1000.0)));
        });
    }

    @Override
    public void versionIncompatible() {
        ui.post(() -> evento.setText(R.string.evento_version));
    }

    @Override
    public void bluetoothApagado() {
        ui.post(() -> estado.setText(R.string.estado_bt_apagado));
    }

    private static String decimal(double valor) {
        return String.format(Locale.getDefault(), "%.1f", valor);
    }
}
