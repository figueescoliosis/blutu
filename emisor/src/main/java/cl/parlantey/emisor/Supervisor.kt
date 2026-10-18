package cl.parlantey.emisor

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import android.util.Log
import cl.parlantey.core.ByeRecibidoException
import cl.parlantey.core.FuenteTono
import cl.parlantey.core.Hello
import cl.parlantey.core.PipelineEmisor
import cl.parlantey.core.Protocolo
import cl.parlantey.core.ProtocoloException
import cl.parlantey.core.ResultadoPrueba
import cl.parlantey.core.SesionEmisor
import cl.parlantey.core.Track
import cl.parlantey.core.VersionIncompatibleException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

/** Pedidos de la UI al hilo de E/S. */
sealed interface Comando {
    data class IniciarTono(val opcion: OpcionPreset, val tasa: Int) : Comando

    data object DetenerTono : Comando

    data class Probar(val wifi: WifiGalaxy) : Comando
}

/**
 * Supervisor único de la conexión con un receptor. Un hilo ("enlace") hace toda la E/S: conecta,
 * espera el HELLO, da pasos al pipeline del tono, envía un PING por segundo y corre la prueba de
 * enlace. Otro ("vigía") cierra el socket si se vence un plazo o pasan 4 s sin tramas del receptor.
 *
 * | Causa del corte | Acción |
 * |---|---|
 * | EOF, IOException o watchdog (ya hubo HELLO) | reconectar con backoff 1, 2, 4, 8, 10… s (máx. 5 min) |
 * | BYE(0) del receptor | terminar: "El receptor se detuvo" |
 * | BYE(1), BYE(2) o ProtocoloException | terminar con el error visible |
 * | Falla el primer connect() (nunca hubo HELLO) | terminar con el error, sin backoff |
 * | Detener (UI o notificación) | sin reintentos |
 *
 * Con el Bluetooth apagado espera sin contar intentos; al encenderse ([bluetoothEncendido])
 * reintenta ~1 s después con el backoff en cero.
 */
class Supervisor(
    private val adaptador: BluetoothAdapter,
    private val dispositivo: Dispositivo,
    private val alTerminar: () -> Unit,
) {
    private val hiloEnlace = Thread(::ejecutar, "ParlanteY-enlace")
    private val hiloVigia = Thread(::vigilar, "ParlanteY-vigia")
    private val comandos = LinkedBlockingQueue<Comando>()
    private val monitor = Object()

    @Volatile private var detenido = false
    @Volatile private var btEncendido = false
    @Volatile private var conexion: ConexionReceptor? = null
    @Volatile private var sesion: SesionEmisor? = null
    /** System.nanoTime límite de la operación en curso (0 = sin plazo). */
    @Volatile private var plazoNs = 0L

    // --- Solo el hilo de enlace (salvo esperandoSonarDesde).
    private var insecurePrimero = false
    private var pipeline: PipelineEmisor? = null
    private var resumen: ResumenTono? = null
    /** cortes de las conexiones anteriores de la prueba de tono actual. */
    private var cortesPrevios = 0L
    private var cortesConexion = 0L
    /**
     * STATE.cortes al empezar el tono en esta conexión: el receptor cuenta por conexión, así que
     * un segundo tono en la misma conexión no debe heredar los cortes del primero.
     */
    private var cortesBase = 0L
    /** elapsedRealtime del último STATE_ON con un tono en curso, hasta que vuelve a sonar. */
    @Volatile private var esperandoSonarDesde = 0L

    fun iniciar() {
        hiloEnlace.start()
        hiloVigia.start()
    }

    fun comando(c: Comando) {
        comandos.offer(c)
    }

    /** Detener: sin reintentos. Si hay conexión, el hilo de enlace envía DETENER y BYE(0) antes de cerrar. */
    fun detener() {
        synchronized(monitor) {
            // Con el mismo monitor que plazo(): el plazo de la despedida no se puede pisar.
            detenido = true
            plazoNs = System.nanoTime() + TIEMPO_DESPEDIDA_NS
            monitor.notifyAll()
        }
        if (sesion == null) conexion?.cerrar() // conectando o esperando el HELLO: cortar ya
    }

    fun bluetoothEncendido() {
        btEncendido = true
        if (pipeline != null || resumen?.terminado == false) esperandoSonarDesde = SystemClock.elapsedRealtime()
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun publicar(cambio: (EstadoServicio) -> EstadoServicio) = EstadoEmisor.actualizar(cambio)

    private fun publicarEnlace(e: Enlace) = publicar { it.copy(enlace = e) }

    // --- Hilo de enlace.

    private fun ejecutar() {
        var intento = 0
        var huboHello = false
        var desdeFallaMs = 0L
        try {
            while (!detenido) {
                if (!adaptador.isEnabled) {
                    publicarEnlace(Enlace.BluetoothApagado(dispositivo))
                    esperarBluetooth()
                    intento = 0
                    desdeFallaMs = 0
                    continue
                }
                publicarEnlace(Enlace.Conectando(dispositivo, intento))
                val c = ConexionReceptor(adaptador.getRemoteDevice(dispositivo.direccion))
                conexion = c
                var falla: Exception? = null
                try {
                    if (detenido) break
                    plazo(TIEMPO_CONEXION_MS)
                    c.conectar(insecurePrimero)
                    insecurePrimero = c.insecure
                    plazo(TIEMPO_HELLO_MS)
                    val s = c.sesion()
                    val hello = s.esperarHello()
                    plazo(0)
                    sesion = s
                    huboHello = true
                    intento = 0
                    desdeFallaMs = 0
                    conectado(s, hello)
                } catch (e: Exception) {
                    falla = e
                    // Un BYE o una trama inválida del receptor puede llegar al lector justo después de que
                    // una escritura falle: darle un momento antes de cerrar, para clasificar bien.
                    if (!detenido && e is IOException) c.esperarFinLector(ESPERA_CAUSA_MS)
                } finally {
                    // Limpiar antes de esperar el reintento: el vigía no debe ver esta conexión.
                    plazo(0)
                    sesion = null
                    conexion = null
                    c.cerrar()
                    pipeline?.desconectar()
                }
                if (detenido || falla == null) break
                val reintentar = clasificar(c.causaLector() ?: falla, falla, huboHello, c) ?: break
                if (!adaptador.isEnabled) continue // se apagó el Bluetooth: no cuenta como intento
                val ahora = SystemClock.elapsedRealtime()
                if (desdeFallaMs == 0L) desdeFallaMs = ahora
                if (ahora - desdeFallaMs > MAX_RECONEXION_MS) {
                    terminar("No se pudo reconectar con ${dispositivo.nombre} en 5 minutos ($reintentar).")
                    break
                }
                val espera = minOf(1000L shl minOf(intento, 4), MAX_ESPERA_MS)
                intento++
                publicarEnlace(Enlace.Reconectando(dispositivo, intento, ahora + espera, reintentar))
                if (esperarReintento(espera)) {
                    intento = 0 // STATE_ON: backoff en cero
                    desdeFallaMs = 0
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "supervisor", t)
            terminar("Error inesperado: $t")
        } finally {
            terminarTono()
            publicarEnlace(Enlace.Inactivo)
            alTerminar()
        }
    }

    /**
     * Decide qué hacer con una falla: devuelve la causa (texto) si hay que reintentar, o null si
     * la sesión termina (y publica el error). [causaLector] es lo que vio el lector de la sesión
     * (un BYE o una trama inválida del receptor pesan más que el error de escritura [falla]).
     */
    private fun clasificar(causaLector: Exception, falla: Exception, huboHello: Boolean, c: ConexionReceptor): String? {
        val e = if (causaLector is ByeRecibidoException || causaLector is ProtocoloException) causaLector else falla
        Log.i(TAG, "conexión terminada: $e")
        when (e) {
            is VersionIncompatibleException -> terminar(
                "El receptor usa el protocolo v${e.versionRemota} y este emisor el v${Protocolo.VERSION}. " +
                    "Instala la misma versión de ParlanteY en los dos teléfonos.",
            )
            is ByeRecibidoException -> when (e.codigo) {
                Protocolo.BYE_NORMAL -> publicar { it.copy(aviso = "El receptor se detuvo.") }
                Protocolo.BYE_VERSION -> terminar("El receptor usa otra versión del protocolo. Actualiza las dos apps.")
                else -> terminar("El receptor cerró la sesión por un error de protocolo (BYE ${e.codigo}).")
            }
            is ProtocoloException -> terminar("Error de protocolo: ${e.message}")
            is IOException -> return when {
                huboHello && c.vencida -> "el receptor dejó de responder"
                huboHello -> "se perdió la conexión"
                !adaptador.isEnabled -> null.also { terminar("El Bluetooth de este teléfono está apagado.") }
                c.vencida && sesionAbierta(c) ->
                    null.also { terminar("Conectado, pero el receptor no envió HELLO a tiempo.") }
                else -> null.also {
                    terminar(
                        "No se pudo conectar con ${dispositivo.nombre}. ¿Está abierto ParlanteY Receptor en el " +
                            "Galaxy Y? (${e.message})",
                    )
                }
            }
            else -> {
                Log.e(TAG, "falla inesperada", e)
                terminar("Error inesperado: $e")
            }
        }
        return null
    }

    private fun sesionAbierta(c: ConexionReceptor): Boolean = try {
        c.sesion()
        true
    } catch (_: IOException) {
        false
    }

    private fun terminar(mensaje: String) = publicar { it.copy(error = mensaje) }

    /** Conexión establecida: bucle de E/S hasta que se corte o se pida detener. */
    private fun conectado(s: SesionEmisor, hello: Hello) {
        val desde = SystemClock.elapsedRealtime()
        var enlace = Enlace.Conectado(dispositivo, InfoReceptor(hello), desde)
        publicar { it.copy(enlace = enlace, error = null, aviso = null) }
        Log.i(TAG, "conectado a ${hello.nombre} (API ${hello.apiLevel}${if (insecurePrimero) ", insecure" else ""})")

        // El receptor cuenta los cortes por conexión: se acumulan los de la conexión anterior.
        cortesPrevios += cortesConexion
        cortesConexion = 0
        cortesBase = 0
        pipeline?.let { p ->
            // Reconexión con el tono en curso: CONFIG + TRACK de nuevo y sigue sonando.
            p.conectar(s.escritor(), s::estado)
            s.setAudioActivo(true)
            resumen = resumen?.copy(reconexiones = resumen!!.reconexiones + 1)
        }
        var proximoPing = 0L
        var estadosVistos = -1L
        while (true) {
            s.comprobar()
            if (detenido) {
                despedirse(s)
                return
            }
            val c = comandos.poll()
            if (c != null) {
                enlace = atender(c, s, hello, enlace)
                continue
            }
            val ahora = System.nanoTime()
            if (ahora - proximoPing >= 0) {
                s.enviarPing()
                proximoPing = ahora + SesionEmisor.INTERVALO_PING_NS
                val rtt = s.ultimoRttNs()
                if (rtt > 0) {
                    enlace = enlace.copy(rttMs = rtt / 1e6)
                    val e = enlace
                    publicar { it.copy(enlace = e) }
                }
            }
            var espera = ESPERA_INACTIVO_MS
            val p = pipeline
            if (p != null) {
                espera = p.paso(ahora)
                if (espera == PipelineEmisor.FIN) {
                    finTono(s)
                    espera = 0
                }
            }
            val n = s.estadosRecibidos()
            if (n != estadosVistos) {
                estadosVistos = n
                alRecibirEstado(s, pipeline)
            }
            if (espera > 0) {
                val hastaPing = (proximoPing - System.nanoTime()) / 1_000_000L
                // Vuelve enseguida si llegó un STATE desde el último que se procesó.
                s.esperarEstadoTras(estadosVistos, minOf(espera, maxOf(1L, hastaPing)))
            }
        }
    }

    private fun atender(c: Comando, s: SesionEmisor, hello: Hello, enlace: Enlace.Conectado): Enlace.Conectado =
        when (c) {
            is Comando.IniciarTono -> {
                pipeline?.let {
                    it.pedirDetener()
                    it.paso(System.nanoTime()) // envía DETENER
                    finTono(s)
                }
                val kBps = EstadoEmisor.estado.value.ultimoKBps
                val preset = c.opcion.resolver(kBps)
                val p = PipelineEmisor(
                    FuenteTono(c.tasa, 2),
                    preset,
                    Track("Tono de prueba", "440 Hz", 0),
                    PipelineEmisor.OBJETIVO_ARCHIVOS_MS,
                )
                p.conectar(s.escritor(), s::estado)
                s.setAudioActivo(true)
                pipeline = p
                cortesPrevios = 0
                cortesConexion = 0
                cortesBase = s.estado()?.cortes ?: 0
                esperandoSonarDesde = 0
                val cfg = p.config()
                val r = ResumenTono(
                    inicio = LocalDateTime.now().format(FORMATO_HORA),
                    desdeMs = SystemClock.elapsedRealtime(),
                    hastaMs = SystemClock.elapsedRealtime(),
                    preset = preset,
                    opcion = c.opcion,
                    tasaSalida = cfg.sampleRate,
                    canales = cfg.canales,
                )
                resumen = r
                publicar {
                    it.copy(
                        tono = Tono(c.opcion, preset, c.tasa, cfg.sampleRate, cfg.canales),
                        pruebasTono = it.pruebasTono + r,
                    )
                }
                Log.i(TAG, "tono: $preset, ${c.tasa} Hz → ${cfg.sampleRate} Hz × ${cfg.canales}")
                enlace
            }
            Comando.DetenerTono -> {
                pipeline?.pedirDetener()
                enlace
            }
            is Comando.Probar -> {
                if (pipeline != null) {
                    enlace
                } else {
                    val midiendo = enlace.copy(midiendo = true)
                    publicar { it.copy(enlace = midiendo) }
                    plazo(TIEMPO_PRUEBA_MS)
                    val r = s.probarEnlace(PINGS_PRUEBA, DURACION_PRUEBA_MS, SesionEmisor.LARGO_TRAMA_PRUEBA)
                    plazo(0)
                    val m = medicion(r, hello.nombre, c.wifi)
                    val listo = enlace.copy(midiendo = false, rttMs = r.rttPromMs())
                    publicar { it.copy(enlace = listo, mediciones = it.mediciones + m) }
                    listo
                }
            }
        }

    private fun alRecibirEstado(s: SesionEmisor, p: PipelineEmisor?) {
        val e = s.estado() ?: return
        if (p == null) return
        cortesConexion = (e.cortes - cortesBase).coerceAtLeast(0)
        val vivo = EnVivo(e.bufferMs, cortesPrevios + cortesConexion, e.reproduciendo, p.nivelEstimadoMs(), e.ultimoSeq)
        val ahora = SystemClock.elapsedRealtime()
        var recuperacion: Long? = null
        if (esperandoSonarDesde != 0L && e.reproduciendo) {
            recuperacion = ahora - esperandoSonarDesde
            esperandoSonarDesde = 0
            Log.i(TAG, "volvió a sonar ${recuperacion} ms después de encender el Bluetooth")
        }
        val r = resumen?.let { r ->
            r.copy(
                hastaMs = ahora,
                cortes = vivo.cortes,
                bufferMinMs = if (e.reproduciendo) minOf(r.bufferMinMs, e.bufferMs) else r.bufferMinMs,
                bufferMaxMs = if (e.reproduciendo) maxOf(r.bufferMaxMs, e.bufferMs) else r.bufferMaxMs,
                bufferSumaMs = r.bufferSumaMs + if (e.reproduciendo) e.bufferMs else 0,
                muestrasBuffer = r.muestrasBuffer + if (e.reproduciendo) 1 else 0,
                recuperacionMs = recuperacion ?: r.recuperacionMs,
            )
        }
        resumen = r
        publicar { est ->
            est.copy(
                tono = est.tono?.copy(vivo = vivo),
                pruebasTono = if (r == null) est.pruebasTono else est.pruebasTono.dropLast(1) + r,
            )
        }
    }

    private fun finTono(s: SesionEmisor?) {
        pipeline = null
        s?.setAudioActivo(false)
        terminarTono()
    }

    /** Cierra el resumen del tono en curso (si hay) y lo quita de la UI. */
    private fun terminarTono() {
        val r = resumen?.copy(terminado = true, hastaMs = SystemClock.elapsedRealtime())
        resumen = null
        pipeline = null
        publicar { est ->
            est.copy(
                tono = null,
                pruebasTono = if (r == null) est.pruebasTono else est.pruebasTono.dropLast(1) + r,
            )
        }
    }

    /** Fin pedido por el usuario: DETENER si hay tono, BYE(0) y cierre (el vigía corta a los 2 s). */
    private fun despedirse(s: SesionEmisor) {
        try {
            pipeline?.let {
                it.pedirDetener()
                it.paso(System.nanoTime())
            }
            s.despedir(Protocolo.BYE_NORMAL)
        } catch (_: IOException) {
            // Se cierra igual.
        }
        finTono(s)
    }

    private fun plazo(ms: Long) {
        synchronized(monitor) {
            if (detenido) return // se mantiene el plazo de la despedida (detener)
            plazoNs = if (ms == 0L) 0L else System.nanoTime() + ms * 1_000_000L
        }
    }

    /** Espera a que se encienda el Bluetooth (o detener), y ~1 s más. */
    private fun esperarBluetooth() {
        synchronized(monitor) {
            while (!detenido && !adaptador.isEnabled) {
                btEncendido = false
                monitor.wait(1000)
            }
        }
        if (!detenido) esperarReintento(ESPERA_TRAS_ENCENDER_MS)
        btEncendido = false
    }

    /**
     * Espera [ms] antes de reintentar. Si el Bluetooth se enciende durante la espera, corta y
     * reintenta ~1 s después. Devuelve true en ese caso.
     */
    private fun esperarReintento(ms: Long): Boolean {
        val fin = SystemClock.elapsedRealtime() + ms
        synchronized(monitor) {
            while (!detenido) {
                if (btEncendido) {
                    btEncendido = false
                    monitor.wait(ESPERA_TRAS_ENCENDER_MS)
                    return true
                }
                val resto = fin - SystemClock.elapsedRealtime()
                if (resto <= 0) return false
                monitor.wait(resto)
            }
        }
        return false
    }

    // --- Hilo vigía.

    private fun vigilar() {
        while (true) {
            try {
                Thread.sleep(VIGILANCIA_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!hiloEnlace.isAlive) return
            val c = conexion ?: continue
            val ahora = System.nanoTime()
            val p = plazoNs
            val s = sesion
            if (p != 0L && ahora - p > 0) {
                Log.i(TAG, "plazo vencido: se cierra la conexión")
                c.cerrarPorTiempo()
            } else if (s != null && s.vencida(ahora)) {
                Log.i(TAG, "4 s sin tramas del receptor: se cierra la conexión")
                c.cerrarPorTiempo()
            }
        }
    }

    private fun medicion(r: ResultadoPrueba, receptor: String, wifi: WifiGalaxy) = Medicion(
        hora = LocalDateTime.now().format(FORMATO_HORA),
        receptor = receptor,
        wifi = wifi,
        kBpsReceptor = r.kBpsReceptor(),
        kBpsEmisor = r.kBpsEmisor(),
        rttMinMs = r.rttMinMs(),
        rttPromMs = r.rttPromMs(),
        rttMaxMs = r.rttMaxMs(),
        bytes = r.bytesRecibidos,
        duracionMs = r.duracionReceptorMs,
        bytesCoinciden = r.bytesRecibidos == r.bytesEnviados,
    )

    companion object {
        const val TAG = "ParlanteY"
        private const val TIEMPO_CONEXION_MS = 30_000L // dos intentos (segura + insecure)
        private const val TIEMPO_HELLO_MS = 10_000L
        private const val TIEMPO_PRUEBA_MS = 150_000L
        private const val TIEMPO_DESPEDIDA_NS = 2_000_000_000L
        private const val ESPERA_INACTIVO_MS = 250L
        private const val MAX_ESPERA_MS = 10_000L
        private const val MAX_RECONEXION_MS = 5 * 60_000L
        private const val ESPERA_TRAS_ENCENDER_MS = 1_000L
        private const val VIGILANCIA_MS = 250L
        private const val ESPERA_CAUSA_MS = 300L
        const val PINGS_PRUEBA = 20
        const val DURACION_PRUEBA_MS = 5000L
        val FORMATO_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
