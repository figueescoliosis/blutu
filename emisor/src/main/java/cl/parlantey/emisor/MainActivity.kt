package cl.parlantey.emisor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.parlantey.emisor.ui.Acciones
import cl.parlantey.emisor.ui.Pantalla
import cl.parlantey.emisor.ui.TemaEmisor

class MainActivity : ComponentActivity() {

    private val vm: EmisorViewModel by viewModels()

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refrescar() }

    private val pedirEncender =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refrescar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val acciones = Acciones(
            pedirPermiso = ::pedirPermisoBluetooth,
            abrirAjustesApp = ::abrirAjustesApp,
            encenderBluetooth = ::encenderBluetooth,
            refrescar = vm::refrescar,
            conectar = vm::conectar,
            probar = vm::probarEnlace,
            desconectar = vm::desconectar,
            elegirWifi = vm::elegirWifi,
            copiarMediciones = ::copiarMediciones,
            borrarMediciones = vm::borrarMediciones,
            cerrarError = vm::cerrarError,
        )
        setContent {
            TemaEmisor {
                val ui by vm.ui.collectAsStateWithLifecycle()
                Pantalla(ui, acciones)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refrescar()
    }

    /** BLUETOOTH_CONNECT es un permiso de runtime solo desde API 31; antes basta el manifiesto. */
    private fun pedirPermisoBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pedirPermiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
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

    private fun copiarMediciones() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ParlanteY", vm.textoParaNotas()))
        // Desde Android 13 el sistema ya muestra su propio aviso al copiar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Mediciones copiadas", Toast.LENGTH_SHORT).show()
        }
    }
}
