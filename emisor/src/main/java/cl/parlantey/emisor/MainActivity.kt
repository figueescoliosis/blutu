package cl.parlantey.emisor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.parlantey.emisor.ui.Acciones
import cl.parlantey.emisor.ui.Pantalla
import cl.parlantey.emisor.ui.TemaEmisor

class MainActivity : ComponentActivity() {

    private val vm: EmisorViewModel by viewModels()

    private val pedirPermisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refrescar() }

    private val pedirEncender =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refrescar() }

    /** Consentimiento de MediaProjection: con él, el servicio captura todo el audio. */
    private val pedirProyeccion =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val datos = r.data
            if (r.resultCode == RESULT_OK && datos != null) vm.iniciarCaptura(r.resultCode, datos)
        }

    private val pedirAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                pedirConsentimiento()
            } else {
                Toast.makeText(this, "Sin el permiso de grabar audio no se puede enviar todo el audio", Toast.LENGTH_LONG).show()
            }
        }

    /** Selector de documentos con varios a la vez; el permiso de lectura se hace persistente. */
    private val elegirCanciones =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.agregarCanciones(it) }

    /** Encender o apagar el Bluetooth desde Ajustes rápidos no pausa la Activity. */
    private val cambiosBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refrescar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val acciones = Acciones(
            pedirPermiso = ::pedirPermisoBluetooth,
            abrirAjustesApp = ::abrirAjustesApp,
            quitarRestriccionBateria = ::quitarRestriccionBateria,
            encenderBluetooth = ::encenderBluetooth,
            refrescar = vm::refrescar,
            conectar = vm::conectar,
            probar = vm::probarEnlace,
            desconectar = vm::desconectar,
            elegirWifi = vm::elegirWifi,
            elegirPreset = vm::elegirPreset,
            elegirTasa = vm::elegirTasa,
            iniciarTono = vm::iniciarTono,
            detenerAudio = vm::detenerAudio,
            // audio/* no cubre los .ogg/.opus/.flac que algunos proveedores declaran como application/*.
            agregarCanciones = { elegirCanciones.launch(TIPOS_AUDIO) },
            vaciarCola = vm::vaciarCola,
            reproducir = vm::reproducir,
            alternarPausa = vm::alternarPausa,
            siguiente = vm::siguiente,
            anterior = vm::anterior,
            copiarMusica = { copiar(vm.textoNotasFase3()) },
            borrarMusica = vm::borrarMusica,
            iniciarCaptura = ::empezarCaptura,
            elegirSilenciar = vm::elegirSilenciar,
            copiarCapturas = { copiar(vm.textoNotasFase4()) },
            borrarCapturas = vm::borrarCapturas,
            copiarMediciones = { copiar(vm.textoParaNotas()) },
            borrarMediciones = vm::borrarMediciones,
            copiarPruebasTono = { copiar(vm.textoNotasFase2()) },
            borrarPruebasTono = vm::borrarPruebasTono,
            cerrarError = vm::cerrarError,
        )
        setContent {
            TemaEmisor {
                val ui by vm.ui.collectAsStateWithLifecycle()
                Pantalla(ui, acciones)
            }
        }
        // Al abrir la app se piden juntos BLUETOOTH_CONNECT y POST_NOTIFICATIONS (si faltan).
        if (savedInstanceState == null && permisosFaltantes().isNotEmpty()) pedirPermisoBluetooth()
    }

    private fun permisosFaltantes(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            cambiosBluetooth,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        vm.refrescar()
    }

    override fun onStop() {
        unregisterReceiver(cambiosBluetooth)
        super.onStop()
    }

    /**
     * BLUETOOTH_CONNECT es un permiso de runtime desde API 31 (antes basta el manifiesto) y
     * POST_NOTIFICATIONS desde API 33. Sin notificaciones el servicio corre igual.
     */
    private fun pedirPermisoBluetooth() {
        val faltan = permisosFaltantes()
        if (faltan.isNotEmpty()) pedirPermisos.launch(faltan.toTypedArray())
    }

    private fun encenderBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermisoBluetooth()
            return
        }
        pedirEncender.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun abrirAjustesApp() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /**
     * El diálogo directo del sistema («¿Permitir que se ejecute en segundo plano?»); si el fabricante
     * lo quitó, la lista de optimización de batería. Al volver, onResume refresca la tarjeta.
     */
    @SuppressLint("BatteryLife") // app instalada a mano, no de Play: el envío en vivo lo necesita
    private fun quitarRestriccionBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                abrirAjustesApp()
            }
        }
    }

    /** Permiso de grabar audio → consentimiento de MediaProjection → el servicio. */
    private fun empezarCaptura() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (vm.tienePermisoAudio()) pedirConsentimiento() else pedirAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun pedirConsentimiento() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: pedir la pantalla completa (con una sola app, la captura de audio queda limitada a ella).
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        pedirProyeccion.launch(intent)
    }

    private companion object {
        val TIPOS_AUDIO = arrayOf("audio/*", "application/ogg", "application/x-flac", "application/flac")
    }

    private fun copiar(texto: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ParlanteY", texto))
        // Desde Android 13 el sistema ya muestra su propio aviso al copiar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copiado para NOTAS.md", Toast.LENGTH_SHORT).show()
        }
    }
}
