package cl.parlantey.emisor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import cl.parlantey.core.Hello
import cl.parlantey.core.Protocolo
import cl.parlantey.core.ResultadoPrueba
import cl.parlantey.core.SesionEmisor
import java.io.IOException

/**
 * Conexión RFCOMM con el receptor. Todas las llamadas bloquean: usarlas desde un hilo de E/S.
 * Para cortar una llamada bloqueada desde otro hilo, usar [cerrar] o [cerrarPorTiempo].
 */
@SuppressLint("MissingPermission") // EmisorViewModel solo la crea con BLUETOOTH_CONNECT concedido.
class ConexionReceptor(private val dispositivo: BluetoothDevice) {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var sesion: SesionEmisor? = null
    @Volatile private var cerrada = false

    /** true si la cerró [cerrarPorTiempo] (el receptor dejó de responder). */
    @Volatile var vencida = false
        private set

    /** Abre el socket: primero la variante segura y, si falla, la insecure. */
    fun conectar() {
        val s = try {
            abrir(segura = true)
        } catch (e: IOException) {
            if (cerrada) throw e
            try {
                abrir(segura = false)
            } catch (e2: IOException) {
                e2.addSuppressed(e)
                throw e2
            }
        }
        sesion = SesionEmisor(s.inputStream, s.outputStream)
    }

    private fun abrir(segura: Boolean): BluetoothSocket {
        val s = if (segura) {
            dispositivo.createRfcommSocketToServiceRecord(Protocolo.UUID_SERVICIO)
        } else {
            dispositivo.createInsecureRfcommSocketToServiceRecord(Protocolo.UUID_SERVICIO)
        }
        socket = s
        if (cerrada) {
            cerrarSilencioso(s)
            throw IOException("conexión cancelada")
        }
        try {
            s.connect()
            return s
        } catch (e: IOException) {
            cerrarSilencioso(s)
            throw e
        }
    }

    fun esperarHello(): Hello = sesionAbierta().esperarHello()

    /** RTT de un PING/PONG, en nanosegundos. */
    fun ping(): Long = sesionAbierta().ping()

    fun probarEnlace(): ResultadoPrueba =
        sesionAbierta().probarEnlace(PINGS_PRUEBA, DURACION_PRUEBA_MS, SesionEmisor.LARGO_TRAMA_PRUEBA)

    /** Envía BYE si todavía se puede y cierra. */
    fun despedir() {
        if (!cerrada) {
            try {
                sesion?.despedir(Protocolo.BYE_NORMAL)
            } catch (_: IOException) {
                // Se cierra igual.
            }
        }
        cerrar()
    }

    fun cerrarPorTiempo() {
        vencida = true
        cerrar()
    }

    fun cerrar() {
        cerrada = true
        socket?.let(::cerrarSilencioso)
    }

    private fun sesionAbierta(): SesionEmisor = sesion ?: throw IOException("no conectado")

    private fun cerrarSilencioso(s: BluetoothSocket) {
        try {
            s.close()
        } catch (_: IOException) {
            // Nada que hacer.
        }
    }

    companion object {
        const val PINGS_PRUEBA = 20
        const val DURACION_PRUEBA_MS = 5000L
    }
}
