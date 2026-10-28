package cl.parlantey.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lado emisor (cliente) de una conexión. {@link #esperarHello} lee el HELLO en el hilo que llama y,
 * si todo está bien, arranca un hilo lector que despacha lo que manda el receptor:
 * <ul>
 * <li>PONG → {@link #ping}, y el RTT en vivo ({@link #ultimoRttNs});</li>
 * <li>TEST_RESULT → {@link #probarEnlace};</li>
 * <li>STATE → {@link #estado} (y despierta a {@link #esperarEstado});</li>
 * <li>MIC_CONFIG / MIC_AUDIO (v4) → la cola de {@link #recibirMicrofono}, si hay; si no, se descartan;</li>
 * <li>BYE, EOF o una trama inválida → la sesión termina: lo que esté esperando, y lo que se llame
 * después, lanza esa causa ({@link #comprobar}).</li>
 * </ul>
 * Las escrituras van por {@link #escritor()}, que es seguro para varios hilos. Una sesión por
 * conexión: un STATE de una conexión anterior nunca llega a la siguiente.
 */
public final class SesionEmisor {

    /** Payload de cada TEST_DATA durante la prueba de enlace. */
    public static final int LARGO_TRAMA_PRUEBA = 4096;
    /** Sin ninguna trama durante 4 s, la conexión se da por muerta (el receptor envía STATE cada 250 ms). */
    public static final long LIMITE_SILENCIO_NS = 4000000000L;
    /** PING de keepalive cada 1 s, siempre (el receptor cierra tras 5 s sin tramas). */
    public static final long INTERVALO_PING_NS = 1000000000L;

    static final long ESPERA_PONG_MS = 5000;
    /** Para el TEST_RESULT: el socket del emisor acepta ~1,5 MB, que a 18 KB/s tardan ~80 s. */
    static final long ESPERA_RESULTADO_MS = 120000;

    private final InputStream entrada;
    private final OutputStream salidaFlujo;
    private final LectorTramas lector;
    private final EscritorTramas escritor;
    private final Reloj reloj;

    private Thread hiloLector;
    private volatile long nanosUltimaTrama;
    private volatile long ultimoRttNs = -1;
    private volatile boolean audioActivo;
    private volatile boolean cerrada;
    private volatile ColaBloques colaMic;
    /** Solo el hilo lector. */
    private Config configMic;

    // --- Protegido por "this".
    private IOException causa;
    private long pingEsperado;
    private boolean pongRecibido;
    private boolean resultadoRecibido;
    private long resultadoBytes;
    private long resultadoMs;
    private Estado estado;
    private long estadosRecibidos;

    public SesionEmisor(InputStream entrada, OutputStream salida) {
        this(entrada, salida, Reloj.SISTEMA);
    }

    public SesionEmisor(InputStream entrada, OutputStream salida, Reloj reloj) {
        this.entrada = entrada;
        this.salidaFlujo = salida;
        this.lector = new LectorTramas(entrada);
        this.escritor = new EscritorTramas(salida);
        this.reloj = reloj;
    }

    /**
     * El micrófono del receptor (v4) va a esta cola, que la reproduce un {@link Reproductor} como en
     * el receptor. Llamar antes de {@link #esperarHello}.
     */
    public void recibirMicrofono(ColaBloques cola) {
        colaMic = cola;
    }

    /**
     * Espera el HELLO del receptor (debe ser la primera trama) y arranca el hilo lector.
     *
     * @throws VersionIncompatibleException si la versión no coincide; antes de lanzarla
     *         se envía BYE con {@link Protocolo#BYE_VERSION}
     */
    public Hello esperarHello() throws IOException {
        if (hiloLector != null) {
            throw new IllegalStateException("el HELLO ya llegó");
        }
        int tipo = lector.leer();
        if (tipo == Protocolo.BYE) {
            throw new ByeRecibidoException(lector.datos().readUnsignedByte());
        }
        if (tipo != Protocolo.HELLO) {
            throw new ProtocoloException("se esperaba HELLO y llegó el tipo 0x" + Integer.toHexString(tipo));
        }
        Hello hello;
        try {
            hello = Hello.leer(lector.datos());
        } catch (VersionIncompatibleException e) {
            try {
                escritor.bye(Protocolo.BYE_VERSION);
            } catch (IOException ignorada) {
                // La conexión se cierra igual; lo importante es informar la incompatibilidad.
            }
            throw e;
        }
        nanosUltimaTrama = reloj.nanos();
        hiloLector = new Thread(new Runnable() {
            @Override
            public void run() {
                leerHastaElFin();
            }
        }, "ParlanteY-lector");
        hiloLector.setDaemon(true);
        hiloLector.start();
        return hello;
    }

    private void leerHastaElFin() {
        IOException fin;
        try {
            while (true) {
                int tipo = lector.leer();
                nanosUltimaTrama = reloj.nanos();
                if (tipo == Protocolo.BYE) {
                    fin = new ByeRecibidoException(lector.datos().readUnsignedByte());
                    break;
                }
                despachar(tipo);
            }
        } catch (IOException e) {
            fin = e;
        } catch (RuntimeException e) {
            fin = new IOException("error en el hilo lector", e);
        }
        synchronized (this) {
            causa = fin;
            notifyAll();
        }
    }

    private void despachar(int tipo) throws IOException {
        switch (tipo) {
            case Protocolo.PONG: {
                long t = lector.datos().readLong();
                ultimoRttNs = reloj.nanos() - t;
                synchronized (this) {
                    // Un PONG con otro timestamp es de un PING anterior o de un keepalive.
                    if (t == pingEsperado) {
                        pongRecibido = true;
                        notifyAll();
                    }
                }
                break;
            }
            case Protocolo.STATE: {
                Estado e = Estado.leer(lector.datos());
                synchronized (this) {
                    estado = e;
                    estadosRecibidos++;
                    notifyAll();
                }
                break;
            }
            case Protocolo.TEST_RESULT: {
                long bytes = lector.datos().readLong();
                long ms = lector.datos().readInt() & 0xFFFFFFFFL;
                synchronized (this) {
                    resultadoBytes = bytes;
                    resultadoMs = ms;
                    resultadoRecibido = true;
                    notifyAll();
                }
                break;
            }
            case Protocolo.MIC_CONFIG:
                recibirMicConfig(Config.leer(lector.datos(), lector.flags()));
                break;
            case Protocolo.MIC_AUDIO:
                recibirMicAudio();
                break;
            default:
                // HELLO repetido, tipos desconocidos o que el emisor no usa: el payload ya se consumió.
                break;
        }
    }

    /** Como CONFIG en {@link SesionReceptor}. */
    private void recibirMicConfig(Config c) throws ProtocoloException {
        ColaBloques cola = colaMic;
        if (cola == null) {
            return;
        }
        if (c.bytesBloque() > cola.bytesPorBloque() || c.canales * c.muestrasPorBloque > Reproductor.MAX_MUESTRAS_BLOQUE) {
            throw new ProtocoloException("micrófono con " + c.muestrasPorBloque + " muestras por bloque no soportado");
        }
        boolean mismoFormato = configMic != null && configMic.sampleRate == c.sampleRate
                && configMic.canales == c.canales && configMic.muestrasPorBloque == c.muestrasPorBloque;
        configMic = c;
        if (mismoFormato) {
            cola.actualizarConfig(c);
        } else {
            cola.nuevaGeneracion(c);
        }
    }

    /** Como AUDIO en {@link SesionReceptor}: no asigna memoria. */
    private void recibirMicAudio() throws IOException {
        ColaBloques cola = colaMic;
        if (lector.largo() < 4) {
            throw new ProtocoloException("MIC_AUDIO sin seq");
        }
        long seq = lector.datos().readInt() & 0xFFFFFFFFL;
        if (cola == null || configMic == null) {
            return;
        }
        if (lector.largo() != configMic.largoAudio()) {
            throw new ProtocoloException("MIC_AUDIO de " + lector.largo() + " bytes; con este MIC_CONFIG son "
                    + configMic.largoAudio());
        }
        ColaBloques.Bloque b = cola.tomarLibre();
        if (b == null) {
            cola.recibido(seq); // la cola está llena: el reproductor está trabado; se descarta
            return;
        }
        int bytes = configMic.bytesBloque();
        lector.datos().readFully(b.datos, 0, bytes);
        b.largo = bytes;
        cola.encolar(b, seq);
    }

    /**
     * Envía un PING y espera su PONG. Devuelve el RTT en nanosegundos. Desde un solo hilo a la vez.
     *
     * @throws IOException si la sesión terminó o el PONG no llega en 5 s
     */
    public long ping() throws IOException {
        long t = reloj.nanos();
        synchronized (this) {
            comprobarLector();
            pingEsperado = t;
            pongRecibido = false;
        }
        escritor.ping(t);
        synchronized (this) {
            long limite = System.nanoTime() + ESPERA_PONG_MS * 1000000L;
            while (!pongRecibido) {
                esperarHasta(limite, "el receptor no respondió el PING");
            }
        }
        return reloj.nanos() - t;
    }

    /** PING de keepalive sin esperar el PONG (que actualiza {@link #ultimoRttNs}). */
    public void enviarPing() throws IOException {
        escritor.ping(reloj.nanos());
    }

    /**
     * Prueba de enlace: {@code pings} PING/PONG y luego TEST_DATA durante {@code duracionMs}
     * con tramas de {@code largoTrama} bytes de payload; la última lleva {@link Protocolo#FLAG_ULTIMA}.
     *
     * @throws IllegalStateException si hay audio en curso ({@link #setAudioActivo})
     */
    public ResultadoPrueba probarEnlace(int pings, long duracionMs, int largoTrama) throws IOException {
        if (pings < 1) {
            throw new IllegalArgumentException("se necesita al menos un ping");
        }
        if (audioActivo) {
            throw new IllegalStateException("la prueba de enlace no puede correr con audio en curso");
        }
        long min = Long.MAX_VALUE;
        long max = 0;
        long suma = 0;
        for (int i = 0; i < pings; i++) {
            long rtt = ping();
            min = Math.min(min, rtt);
            max = Math.max(max, rtt);
            suma += rtt;
        }

        synchronized (this) {
            resultadoRecibido = false;
        }
        long bytesTrama = Protocolo.LARGO_CABECERA + largoTrama;
        long enviados = 0;
        long inicio = System.nanoTime();
        long fin = inicio + duracionMs * 1000000L;
        while (System.nanoTime() - fin < 0) {
            comprobar();
            escritor.testData(largoTrama, false);
            enviados += bytesTrama;
        }
        escritor.testData(largoTrama, true);
        enviados += bytesTrama;

        long bytesRecibidos;
        long duracionReceptorMs;
        synchronized (this) {
            long limite = System.nanoTime() + ESPERA_RESULTADO_MS * 1000000L;
            while (!resultadoRecibido) {
                esperarHasta(limite, "el receptor no envió TEST_RESULT");
            }
            bytesRecibidos = resultadoBytes;
            duracionReceptorMs = resultadoMs;
        }
        long duracionEmisorMs = (System.nanoTime() - inicio) / 1000000L;

        return new ResultadoPrueba(pings, min, suma / pings, max,
                enviados, bytesRecibidos, duracionReceptorMs, duracionEmisorMs);
    }

    /** Marca que hay audio en curso: mientras tanto, {@link #probarEnlace} se rechaza. */
    public void setAudioActivo(boolean activo) {
        audioActivo = activo;
    }

    /** Último STATE recibido en esta conexión, o null si todavía no llega ninguno. */
    public synchronized Estado estado() {
        return estado;
    }

    public synchronized long estadosRecibidos() {
        return estadosRecibidos;
    }

    /**
     * Espera hasta {@code ms} a que llegue un STATE después de esta llamada. Devuelve true si llegó.
     * Para no perder uno que llegó justo antes, usar {@link #esperarEstadoTras}.
     *
     * @throws IOException si la sesión terminó
     */
    public boolean esperarEstado(long ms) throws IOException {
        return esperarEstadoTras(estadosRecibidos(), ms);
    }

    /**
     * Espera hasta {@code ms} a que {@link #estadosRecibidos()} sea distinto de {@code vistos} (el
     * valor que leyó quien llama). Si ya es distinto, vuelve enseguida. Devuelve true si llegó.
     *
     * @throws IOException si la sesión terminó sin que llegara
     */
    public synchronized boolean esperarEstadoTras(long vistos, long ms) throws IOException {
        if (estadosRecibidos != vistos) {
            return true;
        }
        comprobarLector();
        if (ms > 0) {
            long limite = System.nanoTime() + ms * 1000000L;
            while (estadosRecibidos == vistos && causa == null && !cerrada) {
                long resto = (limite - System.nanoTime()) / 1000000L;
                if (resto <= 0) {
                    break;
                }
                esperar(resto);
            }
        }
        if (estadosRecibidos != vistos) {
            return true;
        }
        comprobarLector();
        return false;
    }

    /** {@link Reloj#nanos()} de la última trama recibida (o del HELLO). */
    public long nanosUltimaTrama() {
        return nanosUltimaTrama;
    }

    /** true si pasaron más de {@link #LIMITE_SILENCIO_NS} sin ninguna trama: hay que cerrar. */
    public boolean vencida(long ahoraNs) {
        return hiloLector != null && ahoraNs - nanosUltimaTrama > LIMITE_SILENCIO_NS;
    }

    /** RTT del último PONG (también de los keepalive), en ns; −1 si todavía no hay. */
    public long ultimoRttNs() {
        return ultimoRttNs;
    }

    /** Escritor de la conexión, para CONFIG, TRACK, CONTROL y AUDIO. */
    public EscritorTramas escritor() {
        return escritor;
    }

    /** Causa del fin de la sesión (BYE, EOF, error), o null si sigue viva. */
    public synchronized IOException causa() {
        return causa;
    }

    /**
     * Espera hasta {@code ms} a que termine el hilo lector (sin cerrar nada). Devuelve la causa, o
     * null si sigue vivo.
     */
    public synchronized IOException esperarFin(long ms) {
        long limite = System.nanoTime() + ms * 1000000L;
        while (causa == null && hiloLector != null) {
            long resto = (limite - System.nanoTime()) / 1000000L;
            if (resto <= 0) {
                break;
            }
            try {
                wait(resto);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return causa;
    }

    /** Lanza la causa si la sesión terminó. */
    public synchronized void comprobar() throws IOException {
        comprobarLector();
    }

    /** Envía BYE. No cierra la conexión: eso lo hace {@link #cerrar}. */
    public void despedir(int codigo) throws IOException {
        escritor.bye(codigo);
    }

    /**
     * Cierra los flujos (con un socket, eso lo cierra y desbloquea la lectura en curso) y espera
     * a que termine el hilo lector. Se puede llamar desde cualquier hilo y más de una vez.
     */
    public void cerrar() {
        cerrada = true;
        synchronized (this) {
            notifyAll(); // quien espera un PONG, un STATE o el TEST_RESULT ve que está cerrada
        }
        cerrarSilencioso(entrada);
        cerrarSilencioso(salidaFlujo);
        Thread t = hiloLector;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean cerrada() {
        return cerrada;
    }

    private void comprobarLector() throws IOException {
        if (causa != null) {
            throw causa;
        }
        if (cerrada) {
            throw new IOException("sesión cerrada");
        }
    }

    /** Con el monitor tomado: espera hasta el límite (System.nanoTime) o lanza la causa. */
    private void esperarHasta(long limite, String mensajeTiempo) throws IOException {
        comprobarLector();
        long resto = (limite - System.nanoTime()) / 1000000L;
        if (resto <= 0) {
            throw new IOException(mensajeTiempo);
        }
        esperar(resto); // quien llama revisa su condición y vuelve aquí, que revisa la causa
    }

    private void esperar(long ms) throws IOException {
        try {
            wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("espera interrumpida");
        }
    }

    private static void cerrarSilencioso(Closeable c) {
        try {
            c.close();
        } catch (IOException ignorada) {
            // Nada que hacer: se está cerrando.
        }
    }
}
