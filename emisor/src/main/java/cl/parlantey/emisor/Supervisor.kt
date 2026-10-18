package cl.parlantey.emisor

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import android.util.Log
import cl.parlantey.core.ByeRecibidoException
import cl.parlantey.core.CodificadorPista
import cl.parlantey.core.ColaCaptura
import cl.parlantey.core.ControlCola
import cl.parlantey.core.Estado
import cl.parlantey.core.FlujoEmisor
import cl.parlantey.core.FuenteTono
import cl.parlantey.core.Hello
import cl.parlantey.core.Protocolo
import cl.parlantey.core.ProtocoloException
import cl.parlantey.core.ResultadoPrueba
import cl.parlantey.core.Seq
import cl.parlantey.core.SesionEmisor
import cl.parlantey.core.VersionIncompatibleException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/** Pedidos de la UI (y de la notificación) al hilo de E/S. */
sealed interface Comando {
    data class IniciarTono(val opcion: OpcionPreset, val tasa: Int) : Comando

    /** Detiene lo que suene (tono o música); la conexión sigue. */
    data object DetenerAudio : Comando

    data class Probar(val wifi: WifiGalaxy) : Comando

    /** Reproduce la canción [indice] de [ColaMusica]. */
    data class Reproducir(val indice: Int, val opcion: OpcionPreset) : Comando

    data object Pausar : Comando

    data object Reanudar : Comando

    data object AlternarPausa : Comando

    data object Siguiente : Comando

    data object Anterior : Comando

    data class CambiarPreset(val opcion: OpcionPreset) : Comando

    /** Se agregaron canciones (o se vació la cola). */
    data object ColaCambiada : Comando

    /** Enviar en vivo lo que captura [cola] (la captura ya empezó en el servicio). */
    data class IniciarCaptura(val cola: ColaCaptura, val opcion: OpcionPreset) : Comando
}

/**
 * Supervisor único de la conexión con un receptor. Un hilo ("enlace") hace toda la E/S: conecta,
 * espera el HELLO, da pasos a la cola de reproducción ([ControlCola]: música o tono), envía un
 * PING por segundo y corre la prueba de enlace. Otro ("vigía") cierra el socket si se vence un
 * plazo o pasan 4 s sin tramas del receptor. Los archivos se abren en un tercero ("abrir").
 *
 * La cola sobrevive a las reconexiones: al volver, la canción sigue desde lo que se escuchó.
 * En la primera conexión con un receptor sin prueba de enlace guardada, la prueba corre sola
 * (es la base del preset Auto).
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
    private val contexto: Context,
    private val adaptador: BluetoothAdapter,
    private val dispositivo: Dispositivo,
    /**
     * Terminó el envío de la captura de esta cola (por lo que sea): el servicio la suelta, si sigue
     * siendo la suya (pudo empezar otra). Se llama desde el hilo de enlace.
     */
    private val alTerminarCaptura: (ColaCaptura) -> Unit,
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

    private val ejecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ParlanteY-abrir").apply { isDaemon = true }
    }

    // --- Solo el hilo de enlace (salvo modo y esperandoSonarDesde).
    private var insecurePrimero = false
    private val control = ControlCola(FlujoEmisor.OBJETIVO_ARCHIVOS_MS)
    /** Qué suena (null: nada). */
    @Volatile private var modo: Modo? = null
    private var opcion = OpcionPreset.AUTO
    private var tasaTono = 48000
    private var canciones: List<Cancion> = emptyList()
    private var resumenTono: ResumenTono? = null
    private var resumenMusica: ResumenMusica? = null
    private var resumenCaptura: ResumenCaptura? = null
    private var colaCaptura: ColaCaptura? = null
    /** elapsedRealtime desde que la captura entrega solo silencio (0 = hay señal). */
    private var silencioDesde = 0L
    private var probadoAuto = false
    /** cortes de las conexiones anteriores de lo que suena. */
    private var cortesPrevios = 0L
    private var cortesConexion = 0L
    /**
     * STATE.cortes al empezar a sonar en esta conexión: el receptor cuenta por conexión, así que
     * un segundo tono en la misma conexión no debe heredar los cortes del primero.
     */
    private var cortesBase = 0L
    /** elapsedRealtime del último STATE_ON con audio en curso, hasta que vuelve a sonar. */
    @Volatile private var esperandoSonarDesde = 0L
    // Latencias (elapsedRealtime del toque; 0 = no se mide).
    private var pausaDesde = 0L
    private var siguienteDesde = 0L
    private var numeroAlPedir = 0L
    /** Tras el "siguiente", un STATE ya mostró al receptor llenando (sin sonar) con la pista nueva. */
    private var vioRellenar = false
    private var ultimoReproduciendo = false

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
        if (modo != null) esperandoSonarDesde = SystemClock.elapsedRealtime()
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
                    control.desconectar() // la canción retoma al reconectar
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
            control.detener() // sin conexión no envía nada: solo cierra los archivos
            finAudio(null, completa = false)
            ejecutor.shutdownNow()
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
        // Reconexión con audio en curso: CONFIG + TRACK de nuevo y sigue desde lo que se escuchó.
        control.conectar(s.escritor(), s::estado)
        pausaDesde = 0
        siguienteDesde = 0
        ultimoReproduciendo = false
        if (modo != null) {
            s.setAudioActivo(true)
            resumenTono = resumenTono?.let { it.copy(reconexiones = it.reconexiones + 1) }
            resumenMusica = resumenMusica?.let { it.copy(reconexiones = it.reconexiones + 1) }
            resumenCaptura = resumenCaptura?.let { it.copy(reconexiones = it.reconexiones + 1) }
        }
        val kBpsGuardados = EstadoEmisor.estado.value.kBps(dispositivo.direccion)
        if (!probadoAuto && modo == null && kBpsGuardados.isNaN()) {
            // Primera vez con este receptor: la prueba de enlace decide el preset Auto.
            probadoAuto = true
            Log.i(TAG, "sin prueba de enlace guardada para ${dispositivo.nombre}: se mide ahora")
            enlace = atender(Comando.Probar(WifiGalaxy.SIN_DATO), s, hello, enlace)
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
            if (modo != null) {
                espera = control.paso(ahora)
                if (espera == FlujoEmisor.FIN) {
                    espera = when {
                        control.abriendo() -> ESPERA_ABRIENDO_MS
                        control.finalizada() || control.flujo().detenido() -> {
                            val completa = control.finalizada()
                            if (!completa && control.errores() > 0) {
                                publicar { it.copy(aviso = control.ultimoError()) }
                            }
                            // Cierra el último archivo. Si terminó la cola, el receptor ya sonó todo
                            // y sigue mostrando «Fin de la cola»; si no, recibe DETENER.
                            if (completa) {
                                control.terminar()
                            } else {
                                control.detener()
                                control.paso(System.nanoTime())
                            }
                            finAudio(s, completa)
                            0
                        }
                        else -> ESPERA_INACTIVO_MS
                    }
                }
            }
            val n = s.estadosRecibidos()
            if (n != estadosVistos) {
                estadosVistos = n
                alRecibirEstado(s)
            }
            if (espera > 0) {
                val hastaPing = (proximoPing - System.nanoTime()) / 1_000_000L
                // Vuelve enseguida si llegó un STATE desde el último que se procesó.
                s.esperarEstadoTras(estadosVistos, minOf(espera, maxOf(1L, hastaPing)))
            }
        }
    }

    private fun atender(c: Comando, s: SesionEmisor, hello: Hello, enlace: Enlace.Conectado): Enlace.Conectado {
        when (c) {
            is Comando.IniciarTono -> iniciarTono(s, c)
            Comando.DetenerAudio -> if (modo != null) detenerAudio(s)
            is Comando.Probar -> return probar(s, hello, enlace, c.wifi)
            is Comando.Reproducir -> reproducir(s, c)
            Comando.Pausar -> pausar()
            Comando.Reanudar -> reanudar()
            Comando.AlternarPausa -> if (control.pausado()) reanudar() else pausar()
            Comando.Siguiente -> if (modo == Modo.MUSICA) {
                medirSiguiente()
                control.siguiente()
            }
            Comando.Anterior -> if (modo == Modo.MUSICA) {
                medirSiguiente()
                control.anterior()
            }
            is Comando.CambiarPreset -> {
                opcion = c.opcion
                if (modo == Modo.MUSICA || modo == Modo.CAPTURA) control.cambiarPreset(opcion.resolver(kBps()))
            }
            Comando.ColaCambiada -> colaCambiada(s)
            is Comando.IniciarCaptura -> iniciarCaptura(s, c)
        }
        if (modo != null) publicarReproduccion(null)
        return enlace
    }

    private fun kBps(): Double = EstadoEmisor.estado.value.kBps(dispositivo.direccion)

    private fun probar(s: SesionEmisor, hello: Hello, enlace: Enlace.Conectado, wifi: WifiGalaxy): Enlace.Conectado {
        if (modo != null) return enlace // con audio en curso no se puede
        val midiendo = enlace.copy(midiendo = true)
        publicar { it.copy(enlace = midiendo) }
        plazo(TIEMPO_PRUEBA_MS)
        val r = s.probarEnlace(PINGS_PRUEBA, DURACION_PRUEBA_MS, SesionEmisor.LARGO_TRAMA_PRUEBA)
        plazo(0)
        val m = medicion(r, hello.nombre, wifi)
        val listo = enlace.copy(midiendo = false, rttMs = r.rttPromMs())
        publicar {
            it.copy(
                enlace = listo,
                mediciones = it.mediciones + m,
                kBpsPorReceptor = it.kBpsPorReceptor + (dispositivo.direccion to m.kBpsReceptor),
            )
        }
        return listo
    }

    private fun iniciarTono(s: SesionEmisor, c: Comando.IniciarTono) {
        if (modo != null) detenerAudio(s)
        val preset = c.opcion.resolver(kBps())
        opcion = c.opcion
        tasaTono = c.tasa
        control.modo(false, FlujoEmisor.OBJETIVO_ARCHIVOS_MS)
        control.cola(FabricaTono(c.tasa))
        control.preset(preset)
        control.reproducir(0, 0)
        empezarAudio(s, Modo.TONO)
        val cfg = CodificadorPista(FuenteTono(c.tasa, 2), preset).config(FlujoEmisor.OBJETIVO_ARCHIVOS_MS)
        val ahora = SystemClock.elapsedRealtime()
        val r = ResumenTono(
            inicio = LocalDateTime.now().format(FORMATO_HORA),
            desdeMs = ahora,
            hastaMs = ahora,
            preset = preset,
            opcion = c.opcion,
            tasaSalida = cfg.sampleRate,
            canales = cfg.canales,
        )
        resumenTono = r
        publicar { it.copy(pruebasTono = it.pruebasTono + r) }
        Log.i(TAG, "tono: $preset, ${c.tasa} Hz → ${cfg.sampleRate} Hz × ${cfg.canales}")
    }

    private fun reproducir(s: SesionEmisor, c: Comando.Reproducir) {
        val lista = ColaMusica.canciones.value
        if (c.indice !in lista.indices) return
        if (modo == Modo.TONO || modo == Modo.CAPTURA) detenerAudio(s)
        opcion = c.opcion
        control.modo(false, FlujoEmisor.OBJETIVO_ARCHIVOS_MS)
        if (modo != Modo.MUSICA || lista != canciones) control.cola(FabricaArchivos(contexto, lista, ejecutor))
        canciones = lista
        control.preset(opcion.resolver(kBps()))
        if (modo == Modo.MUSICA) {
            medirSiguiente()
        } else {
            val ahora = SystemClock.elapsedRealtime()
            val r = ResumenMusica(LocalDateTime.now().format(FORMATO_HORA), ahora, ahora, opcion)
            resumenMusica = r
            publicar { it.copy(musica = it.musica + r) }
            empezarAudio(s, Modo.MUSICA)
        }
        control.reproducir(c.indice, 0)
        Log.i(TAG, "música: canción ${c.indice + 1} de ${lista.size}, ${control.preset()}")
    }

    private fun iniciarCaptura(s: SesionEmisor, c: Comando.IniciarCaptura) {
        if (modo != null) detenerAudio(s) // también una captura anterior (y su proyección)
        opcion = c.opcion
        colaCaptura = c.cola
        silencioDesde = 0
        control.modo(true, FlujoEmisor.OBJETIVO_EN_VIVO_MS)
        control.cola(FabricaCaptura(c.cola))
        control.preset(opcion.resolver(kBps()))
        control.reproducir(0, 0)
        empezarAudio(s, Modo.CAPTURA)
        val ahora = SystemClock.elapsedRealtime()
        val r = ResumenCaptura(LocalDateTime.now().format(FORMATO_HORA), ahora, ahora, opcion)
        resumenCaptura = r
        publicar { it.copy(capturas = it.capturas + r) }
        Log.i(TAG, "captura: ${control.preset()}")
    }

    /** Agregar al final no corta nada; cualquier otro cambio (vaciar) detiene la música. */
    private fun colaCambiada(s: SesionEmisor) {
        val lista = ColaMusica.canciones.value
        if (modo != Modo.MUSICA) return
        if (lista.size >= canciones.size && lista.subList(0, canciones.size) == canciones) {
            canciones = lista
            control.cola(FabricaArchivos(contexto, lista, ejecutor))
        } else {
            detenerAudio(s)
        }
    }

    private fun pausar() {
        if (modo == null || modo == Modo.CAPTURA || control.pausado()) return
        control.pausar()
        // Solo se mide si estaba sonando: si no, cualquier STATE "sin sonar" la confirmaría.
        pausaDesde = if (ultimoReproduciendo && !control.abriendo()) SystemClock.elapsedRealtime() else 0
    }

    private fun reanudar() {
        if (modo == null) return
        control.reanudar()
        pausaDesde = 0
    }

    private fun medirSiguiente() {
        siguienteDesde = SystemClock.elapsedRealtime()
        numeroAlPedir = control.flujo().numeroActual()
        vioRellenar = false
        pausaDesde = 0
    }

    private fun empezarAudio(s: SesionEmisor, m: Modo) {
        modo = m
        control.reiniciarErrores()
        s.setAudioActivo(true)
        cortesPrevios = 0
        cortesConexion = 0
        cortesBase = s.estado()?.cortes ?: 0
        esperandoSonarDesde = 0
        pausaDesde = 0
        siguienteDesde = 0
    }

    /** DETENER al receptor (se envía ya) y fin de lo que sonaba. */
    private fun detenerAudio(s: SesionEmisor) {
        control.detener()
        control.paso(System.nanoTime())
        finAudio(s, completa = false)
    }

    private fun alRecibirEstado(s: SesionEmisor) {
        val e = s.estado() ?: return
        val m = modo ?: return
        cortesConexion = (e.cortes - cortesBase).coerceAtLeast(0)
        ultimoReproduciendo = e.reproduciendo
        val vivo = EnVivo(e.bufferMs, cortesPrevios + cortesConexion, e.reproduciendo, control.flujo().nivelEstimadoMs(), e.ultimoSeq)
        val ahora = SystemClock.elapsedRealtime()
        var recuperacion: Long? = null
        if (esperandoSonarDesde != 0L && e.reproduciendo) {
            recuperacion = ahora - esperandoSonarDesde
            esperandoSonarDesde = 0
            Log.i(TAG, "volvió a sonar ${recuperacion} ms después de encender el Bluetooth")
        }
        if (m == Modo.TONO) {
            val r = resumenTono?.let { r ->
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
            resumenTono = r
            if (r != null) publicar { it.copy(pruebasTono = it.pruebasTono.dropLast(1) + r) }
        } else if (m == Modo.MUSICA) {
            val r = resumenMusica?.let { actualizarResumen(it, e, ahora, vivo.cortes) }
            resumenMusica = r
            if (r != null) publicar { it.copy(musica = it.musica.dropLast(1) + r) }
        } else {
            val cola = colaCaptura
            if (cola != null) {
                if (cola.picoYReiniciar() > UMBRAL_SILENCIO) {
                    silencioDesde = 0
                } else if (silencioDesde == 0L) {
                    silencioDesde = ahora
                }
            }
            val latencia = vivo.nivelMs + (cola?.msPendientes() ?: 0)
            val cfg = control.flujo().config()
            val r = resumenCaptura?.let { r ->
                val suena = e.reproduciendo
                r.copy(
                    hastaMs = ahora,
                    cortes = vivo.cortes,
                    presets = (r.presets + control.preset().nombre).distinct(),
                    formato = cfg?.let { "${tasaTexto(it.sampleRate)} kHz ${if (it.canales == 2) "estéreo" else "mono"}" } ?: r.formato,
                    bufferMinMs = if (suena) minOf(r.bufferMinMs, e.bufferMs) else r.bufferMinMs,
                    bufferMaxMs = if (suena) maxOf(r.bufferMaxMs, e.bufferMs) else r.bufferMaxMs,
                    bufferSumaMs = r.bufferSumaMs + if (suena) e.bufferMs else 0,
                    latenciaMinMs = if (suena) minOf(r.latenciaMinMs, latencia) else r.latenciaMinMs,
                    latenciaMaxMs = if (suena) maxOf(r.latenciaMaxMs, latencia) else r.latenciaMaxMs,
                    latenciaSumaMs = r.latenciaSumaMs + if (suena) latencia else 0,
                    muestras = r.muestras + if (suena) 1 else 0,
                    descartadosMs = cola?.msDescartados() ?: r.descartadosMs,
                )
            }
            resumenCaptura = r
            if (r != null) publicar { it.copy(capturas = it.capturas.dropLast(1) + r) }
        }
        publicarReproduccion(vivo)
    }

    private fun actualizarResumen(r0: ResumenMusica, e: Estado, ahora: Long, cortes: Long): ResumenMusica {
        var r = r0.copy(
            hastaMs = ahora,
            cortes = cortes,
            errores = control.errores().toInt(),
            // En pausa el buffer no baja: no cuenta.
            bufferMinMs = if (e.reproduciendo) minOf(r0.bufferMinMs, e.bufferMs) else r0.bufferMinMs,
            bufferMaxMs = if (e.reproduciendo) maxOf(r0.bufferMaxMs, e.bufferMs) else r0.bufferMaxMs,
            bufferSumaMs = r0.bufferSumaMs + if (e.reproduciendo) e.bufferMs else 0,
            muestrasBuffer = r0.muestrasBuffer + if (e.reproduciendo) 1 else 0,
        )
        if (pausaDesde != 0L && !control.pausado()) pausaDesde = 0 // se reanudó (p. ej. con siguiente)
        if (pausaDesde != 0L && !e.reproduciendo) {
            r = r.copy(pausasMs = r.pausasMs + (ahora - pausaDesde))
            pausaDesde = 0
        }
        val f = control.flujo()
        val inicio = f.seqInicioActual()
        if (siguienteDesde != 0L && !control.abriendo() && f.numeroActual() != numeroAlPedir &&
            inicio != Protocolo.SEQ_NINGUNO
        ) {
            // Primero un STATE llenando (tras VACIAR) y después uno sonando con la pista nueva: el
            // flag "reproduciendo" de un STATE puede ser de antes de que el hilo de audio vaciara.
            if (!e.reproduciendo) {
                vioRellenar = true
            } else if (vioRellenar && Seq.distancia(e.ultimoSeq, inicio) >= 0) {
                r = r.copy(siguientesMs = r.siguientesMs + (ahora - siguienteDesde))
                siguienteDesde = 0
            }
        }
        if (pausaDesde != 0L && ahora - pausaDesde > LIMITE_MEDICION_MS) pausaDesde = 0
        if (siguienteDesde != 0L && ahora - siguienteDesde > LIMITE_MEDICION_MS) siguienteDesde = 0
        val i = control.indiceSonando()
        val cancion = canciones.getOrNull(i)
        val cfg = f.config()
        if (e.reproduciendo && cancion != null && i !in r.pistas && cfg != null) {
            val formato = "${tasaTexto(cfg.sampleRate)} kHz ${if (cfg.canales == 2) "estéreo" else "mono"}"
            r = r.copy(
                pistas = r.pistas + i,
                tipos = (r.tipos + cancion.tipo).distinct(),
                formatos = (r.formatos + formato).distinct(),
                presets = (r.presets + control.preset().nombre).distinct(),
            )
        }
        return r
    }

    /** Publica lo que suena; con [vivo] null conserva el último STATE publicado. */
    private fun publicarReproduccion(vivo: EnVivo?) {
        val m = modo ?: return
        val i = control.indiceSonando()
        val cfg = control.flujo().config()
        val cancion = if (m == Modo.MUSICA) canciones.getOrNull(i) else null
        val cola = colaCaptura.takeIf { m == Modo.CAPTURA }
        val silencio = silencioDesde != 0L && SystemClock.elapsedRealtime() - silencioDesde >= AVISO_SILENCIO_MS
        publicar { est ->
            val r = Reproduccion(
                modo = m,
                opcion = opcion,
                preset = control.preset(),
                tasaOrigen = tasaTono,
                indice = i,
                titulo = cancion?.titulo ?: when (m) {
                    Modo.TONO -> FabricaTono.TRACK.titulo
                    Modo.CAPTURA -> FabricaCaptura.TRACK.titulo
                    Modo.MUSICA -> ""
                },
                artista = cancion?.artista ?: if (m == Modo.TONO) FabricaTono.TRACK.artista else "",
                posicionMs = control.posicionMs(),
                duracionMs = cancion?.duracionMs ?: 0,
                pausado = control.pausado(),
                abriendo = control.abriendo(),
                tasaSalida = cfg?.sampleRate ?: 0,
                canales = cfg?.canales ?: 0,
                vivo = vivo ?: est.reproduccion?.vivo,
                error = control.ultimoError(),
                latenciaMs = if (cola != null) (vivo ?: est.reproduccion?.vivo)?.nivelMs?.plus(cola.msPendientes()) ?: 0 else 0,
                descartadosMs = cola?.msDescartados() ?: 0,
                silencio = cola != null && silencio,
            )
            est.copy(reproduccion = r)
        }
    }

    /** Termina lo que sonaba: cierra los resúmenes y lo quita de la UI. */
    private fun finAudio(s: SesionEmisor?, completa: Boolean) {
        val terminada = colaCaptura.takeIf { modo == Modo.CAPTURA }
        modo = null
        s?.setAudioActivo(false)
        val ahora = SystemClock.elapsedRealtime()
        val rt = resumenTono?.copy(terminado = true, hastaMs = ahora)
        val rm = resumenMusica?.copy(terminado = true, hastaMs = ahora, completa = completa, errores = control.errores().toInt())
        val rc = resumenCaptura?.copy(terminado = true, hastaMs = ahora)
        resumenTono = null
        resumenMusica = null
        resumenCaptura = null
        colaCaptura = null
        publicar { est ->
            est.copy(
                reproduccion = null,
                pruebasTono = if (rt == null) est.pruebasTono else est.pruebasTono.dropLast(1) + rt,
                musica = if (rm == null) est.musica else est.musica.dropLast(1) + rm,
                capturas = if (rc == null) est.capturas else est.capturas.dropLast(1) + rc,
            )
        }
        if (terminada != null) alTerminarCaptura(terminada)
    }

    /** Fin pedido por el usuario: DETENER si hay audio, BYE(0) y cierre (el vigía corta a los 2 s). */
    private fun despedirse(s: SesionEmisor) {
        try {
            if (modo != null) {
                control.detener()
                control.paso(System.nanoTime())
            }
            s.despedir(Protocolo.BYE_NORMAL)
        } catch (_: IOException) {
            // Se cierra igual.
        }
        finAudio(s, completa = false)
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
        private const val ESPERA_ABRIENDO_MS = 10L
        private const val LIMITE_MEDICION_MS = 10_000L
        /** |muestra| bajo esto cuenta como silencio (ruido de redondeo). */
        private const val UMBRAL_SILENCIO = 16
        private const val AVISO_SILENCIO_MS = 3000L
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
