package cl.parlantey.emisor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (tipo connectedDevice) dueño de la conexión con el receptor: un
 * [Supervisor] hace la E/S y reconecta. Mantiene un PARTIAL_WAKE_LOCK mientras el supervisor
 * está activo, para que el tono siga y la reconexión ocurra con la pantalla apagada. La UI le
 * habla con intents y lee [EstadoEmisor].
 */
class ServicioEmisor : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var supervisor: Supervisor? = null
    private var enPrimerPlano = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private var renovando = false

    private val renovarWakeLock = object : Runnable {
        override fun run() {
            if (supervisor == null || !renovando) return
            // Con tiempo límite (lo pide Android); se renueva cada minuto mientras haga falta.
            wakeLock.acquire(DURACION_WAKE_LOCK_MS)
            principal.postDelayed(this, RENOVACION_WAKE_LOCK_MS)
        }
    }

    private val cambiosBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val estado = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (estado == BluetoothAdapter.STATE_OFF && EstadoEmisor.estado.value.enlace is Enlace.BluetoothApagado) {
                // Se volvió a apagar antes de que el estado cambiara: soltar el wake lock de STATE_ON.
                actualizarWakeLock(false)
            }
            if (estado == BluetoothAdapter.STATE_ON && supervisor != null) {
                Log.i(Supervisor.TAG, "Bluetooth encendido")
                // Con el Bluetooth apagado se soltó el wake lock: tomarlo ya, antes de reconectar.
                wakeLock.acquire(DURACION_WAKE_LOCK_MS)
                supervisor?.bluetoothEncendido()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Conexión con el receptor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mientras ParlanteY está conectado al Galaxy Y"
                setShowBadge(false)
            },
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParlanteY:emisor")
            .apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(
            this,
            cambiosBluetooth,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // La notificación cambia solo en las transiciones, no con cada STATE.
        alcance.launch {
            EstadoEmisor.estado.map(::textoNotificacion).distinctUntilChanged().collect { (titulo, texto) ->
                if (enPrimerPlano) nm.notify(ID_NOTIFICACION, notificacion(titulo, texto))
            }
        }
        // Con el Bluetooth apagado la espera puede durar horas: sin wake lock hasta que se encienda.
        alcance.launch {
            EstadoEmisor.estado.map { it.enlace is Enlace.BluetoothApagado }.distinctUntilChanged().collect { apagado ->
                actualizarWakeLock(!apagado)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_CONECTAR -> conectar(intent)
            ACCION_DETENER -> supervisor?.detener() ?: terminarSiInactivo()
            ACCION_TONO -> supervisor?.comando(
                Comando.IniciarTono(
                    OpcionPreset.valueOf(intent.getStringExtra(EXTRA_PRESET) ?: OpcionPreset.AUTO.name),
                    intent.getIntExtra(EXTRA_TASA, 48000),
                ),
            )
            ACCION_DETENER_TONO -> supervisor?.comando(Comando.DetenerTono)
            ACCION_PROBAR -> supervisor?.comando(
                Comando.Probar(WifiGalaxy.valueOf(intent.getStringExtra(EXTRA_WIFI) ?: WifiGalaxy.SIN_DATO.name)),
            )
            else -> terminarSiInactivo()
        }
        return START_NOT_STICKY
    }

    private fun conectar(intent: Intent) {
        // startForeground una sola vez, lo primero y antes de cualquier E/S: tras
        // startForegroundService, Android lo exige aunque el pedido no sirva.
        if (!enPrimerPlano) {
            val (titulo, texto) = textoNotificacion(EstadoEmisor.estado.value)
            try {
                ServiceCompat.startForeground(
                    this,
                    ID_NOTIFICACION,
                    notificacion(titulo, texto),
                    // El tipo existe desde API 29; antes, ServiceCompat lo ignora.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
                )
                enPrimerPlano = true
            } catch (e: Exception) {
                Log.e(Supervisor.TAG, "startForeground", e)
                EstadoEmisor.actualizar { it.copy(error = "No se pudo iniciar el servicio en primer plano: ${e.message}") }
                stopSelf()
                return
            }
        }
        if (supervisor != null) return // ya hay una conexión: se ignora
        val direccion = intent.getStringExtra(EXTRA_DIRECCION)
        val adaptador = getSystemService(BluetoothManager::class.java)?.adapter
        if (direccion == null || adaptador == null) {
            terminarSiInactivo()
            return
        }
        val d = Dispositivo(intent.getStringExtra(EXTRA_NOMBRE) ?: direccion, direccion)
        EstadoEmisor.actualizar { it.copy(error = null, aviso = null) }
        getSystemService(NotificationManager::class.java).cancel(ID_FIN)
        val s = Supervisor(adaptador, d) { principal.post(::alTerminarSupervisor) }
        supervisor = s
        actualizarWakeLock(true)
        s.iniciar()
    }

    private fun actualizarWakeLock(hace: Boolean) {
        if (hace && supervisor != null) {
            if (!renovando) {
                renovando = true
                principal.post(renovarWakeLock)
            }
        } else {
            renovando = false
            principal.removeCallbacks(renovarWakeLock)
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun alTerminarSupervisor() {
        supervisor = null
        actualizarWakeLock(false)
        avisarFin()
        terminarSiInactivo()
    }

    /**
     * Si la conexión terminó por un error o un aviso (p. ej. con la pantalla apagada), deja una
     * notificación común que se puede descartar: la del servicio desaparece con él.
     */
    private fun avisarFin() {
        val e = EstadoEmisor.estado.value
        val mensaje = e.error ?: e.aviso ?: return
        val abrir = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(if (e.error != null) "ParlanteY se desconectó" else "ParlanteY")
            .setContentText(mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mensaje))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_FIN, n)
    }

    private fun terminarSiInactivo(): Int {
        if (supervisor == null) {
            if (enPrimerPlano) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                enPrimerPlano = false
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        supervisor?.detener()
        supervisor = null
        actualizarWakeLock(false)
        unregisterReceiver(cambiosBluetooth)
        alcance.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun textoNotificacion(e: EstadoServicio): Pair<String, String> {
        val tono = e.tono?.let { "Tono 440 Hz · ${it.preset.nombre}" }
        return when (val enlace = e.enlace) {
            Enlace.Inactivo -> "ParlanteY" to "Iniciando…"
            is Enlace.Conectando -> "Conectando con ${enlace.dispositivo.nombre}…" to (tono ?: "")
            is Enlace.Conectado -> "Conectado a ${enlace.dispositivo.nombre}" to (tono ?: "Listo para enviar audio")
            is Enlace.Reconectando ->
                "Reconectando con ${enlace.dispositivo.nombre} (intento ${enlace.intento})" to enlace.causa
            is Enlace.BluetoothApagado -> "Bluetooth apagado" to "Se reconecta solo al encenderlo"
        }
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun notificacion(titulo: String, texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detener = PendingIntent.getService(
            this,
            1,
            Intent(this, ServicioEmisor::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACCION_CONECTAR = "cl.parlantey.emisor.CONECTAR"
        const val ACCION_DETENER = "cl.parlantey.emisor.DETENER"
        const val ACCION_TONO = "cl.parlantey.emisor.TONO"
        const val ACCION_DETENER_TONO = "cl.parlantey.emisor.DETENER_TONO"
        const val ACCION_PROBAR = "cl.parlantey.emisor.PROBAR"
        const val EXTRA_NOMBRE = "nombre"
        const val EXTRA_DIRECCION = "direccion"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_TASA = "tasa"
        const val EXTRA_WIFI = "wifi"

        private const val CANAL = "enlace"
        private const val ID_NOTIFICACION = 1
        private const val ID_FIN = 2
        private const val DURACION_WAKE_LOCK_MS = 10 * 60_000L
        private const val RENOVACION_WAKE_LOCK_MS = 60_000L
    }
}
