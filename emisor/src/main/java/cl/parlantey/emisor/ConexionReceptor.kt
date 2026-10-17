package cl.parlantey.emisor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import cl.parlantey.core.Protocolo
import cl.parlantey.core.SesionEmisor
import java.io.IOException

/**
 * Socket RFCOMM con el receptor. [conectar] bloquea: usarlo desde un hilo de E/S. Para cortar
 * una llamada bloqueada desde otro hilo, usar [cerrar] o [cerrarPorTiempo].
 */
@SuppressLint("MissingPermission") // el servicio solo la crea con BLUETOOTH_CONNECT concedido.
class ConexionReceptor(private val dispositivo: BluetoothDevice) {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var sesion: SesionEmisor? = null
    @Volatile private var cerrada = false

    /** true si la cerró [cerrarPorTiempo] (el receptor dejó de responder). */
    @Volatile var vencida = false
        private set

    /** true si el socket abierto es la variante insecure. */
    var insecure = false
        private set

    /**
     * Abre el socket: primero la variante que funcionó la última vez (la segura salvo que
     * [insecurePrimero]) y, si falla, la otra. Después, [insecure] dice cuál quedó.
     */
    fun conectar(insecurePrimero: Boolean) {
        val s = try {
            abrir(insecure = insecurePrimero)
        } catch (e: IOException) {
            if (cerrada) throw e
            try {
                abrir(insecure = !insecurePrimero)
            } catch (e2: IOException) {
                e2.addSuppressed(e)
                throw e2
            }
        }
        sesion = SesionEmisor(s.inputStream, s.outputStream)
    }

    private fun abrir(insecure: Boolean): BluetoothSocket {
        val s = if (insecure) {
            dispositivo.createInsecureRfcommSocketToServiceRecord(Protocolo.UUID_SERVICIO)
        } else {
            dispositivo.createRfcommSocketToServiceRecord(Protocolo.UUID_SERVICIO)
        }
        socket = s
        if (cerrada) {
            cerrarSilencioso(s)
            throw IOException("conexión cancelada")
        }
        try {
            s.connect()
            this.insecure = insecure
            return s
        } catch (e: IOException) {
            cerrarSilencioso(s)
            throw e
        }
    }

    /** La sesión del socket abierto por [conectar]. */
    fun sesion(): SesionEmisor = sesion ?: throw IOException("no conectado")

    fun cerrarPorTiempo() {
        vencida = true
        cerrar()
    }

    /** Cierra el socket (desbloquea lo que esté esperando) y termina el lector de la sesión. */
    fun cerrar() {
        cerrada = true
        socket?.let(::cerrarSilencioso)
        sesion?.cerrar()
    }

    private fun cerrarSilencioso(s: BluetoothSocket) {
        try {
            s.close()
        } catch (_: IOException) {
            // Nada que hacer.
        }
    }
}
