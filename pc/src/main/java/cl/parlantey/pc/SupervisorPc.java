package cl.parlantey.pc;

import cl.parlantey.core.ByeRecibidoException;
import cl.parlantey.core.ColaCaptura;
import cl.parlantey.core.Config;
import cl.parlantey.core.ControlCola;
import cl.parlantey.core.Estado;
import cl.parlantey.core.FlujoEmisor;
import cl.parlantey.core.Fuente;
import cl.parlantey.core.Hello;
import cl.parlantey.core.Preset;
import cl.parlantey.core.Protocolo;
import cl.parlantey.core.ProtocoloException;
import cl.parlantey.core.ResultadoPrueba;
import cl.parlantey.core.SesionEmisor;
import cl.parlantey.core.Track;
import cl.parlantey.core.VersionIncompatibleException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * La conexión del PC con un receptor, como el supervisor del emisor Android pero con un solo modo:
 * enviar en vivo lo que suena en el PC. Un hilo ("enlace") hace toda la E/S: conecta, espera el
 * HELLO, mide el enlace la primera vez, da pasos al {@link ControlCola} (en vivo, objetivo 500 ms),
 * envía un PING por segundo y reconecta con backoff 1, 2, 4, 8, 10 s. Otro ("vigía") corta la
 * conexión si se vence un plazo o pasan 4 s sin tramas del receptor.
 *
 * <p>Lo que pide la ventana ({@link #enviar}, {@link #opcion}, {@link #silenciar}, {@link #probar})
 * es estado deseado: el hilo de enlace lo aplica en su próximo paso.
 */
public final class SupervisorPc {

    static final long TIEMPO_HELLO_MS = 10000;
    static final long TIEMPO_PRUEBA_MS = 150000;
    static final long MAX_ESPERA_MS = 10000;
    static final long ESPERA_INACTIVO_MS = 250;
    static final long ESPERA_ABRIENDO_MS = 10;
    static final int PINGS_PRUEBA = 20;
    static final long DURACION_PRUEBA_MS = 5000;
    /** |muestra| bajo esto es silencio (ruido de redondeo). */
    static final int UMBRAL_SILENCIO = 16;
    static final long AVISO_SILENCIO_NS = 3000000000L;
    /** Tras silenciar, si en este tiempo no llega nada (y antes sí), el silencio también calla la captura. */
    static final long PRUEBA_SILENCIO_NS = 2500000000L;
    static final long MARGEN_SILENCIO_NS = 500000000L;
    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Transporte.Conector conector;
    private final FuenteSistema fuente;
    private final Ajustes ajustes;
    private final Consumer<EstadoPc> oyente;
    private final Dispositivo dispositivo;
    private final Object monitor = new Object();
    private final Thread hiloEnlace;
    private final Thread hiloVigia;

    // --- Pedidos (cualquier hilo).
    private volatile boolean detenido;
    private volatile boolean quiereEnviar;
    private volatile EstadoPc.Opcion opcionPedida;
    private volatile boolean quiereSilenciar;
    private volatile boolean pruebaPedida;

    // --- Para el vigía.
    private volatile Transporte transporte;
    private volatile SesionEmisor sesion;
    private volatile long plazoNs;

    // --- Solo el hilo de enlace.
    private final ControlCola control = new ControlCola(FlujoEmisor.OBJETIVO_EN_VIVO_MS);
    private final EstadoPc estado = new EstadoPc();
    private ColaCaptura cola;
    private EstadoPc.Opcion opcionAplicada;
    private boolean silencioNoSirve;
    private long silenciadoDesdeNs;
    /** Última vez que llegó señal real (0 = nunca en este envío). */
    private long ultimaSenalNs;
    private long inicioEnvioNs;
    private long cortesPrevios;
    private long cortesConexion;
    private long cortesBase;
    private boolean pruebaAutomaticaHecha;
    private Resumen resumen;
    private final List<Resumen> resumenes = new ArrayList<>();

    /** Resumen de un envío, para «Copiar para NOTAS». */
    public static final class Resumen {
        public String inicio;
        public long desdeMs;
        public long hastaMs;
        public String opcion;
        public String presets = "";
        public String formato = "";
        public long cortes;
        public long bufferMin = Long.MAX_VALUE;
        public long bufferMax;
        public long bufferSuma;
        public long latenciaMin = Long.MAX_VALUE;
        public long latenciaMax;
        public long latenciaSuma;
        public long muestras;
        public long descartadosMs;
        public int reconexiones;
        public boolean terminado;

        Resumen copia() {
            Resumen r = new Resumen();
            r.inicio = inicio;
            r.desdeMs = desdeMs;
            r.hastaMs = hastaMs;
            r.opcion = opcion;
            r.presets = presets;
            r.formato = formato;
            r.cortes = cortes;
            r.bufferMin = bufferMin;
            r.bufferMax = bufferMax;
            r.bufferSuma = bufferSuma;
            r.latenciaMin = latenciaMin;
            r.latenciaMax = latenciaMax;
            r.latenciaSuma = latenciaSuma;
            r.muestras = muestras;
            r.descartadosMs = descartadosMs;
            r.reconexiones = reconexiones;
            r.terminado = terminado;
            return r;
        }
    }

    public SupervisorPc(Transporte.Conector conector, FuenteSistema fuente, Ajustes ajustes, Dispositivo dispositivo,
            EstadoPc.Opcion opcion, boolean silenciar, Consumer<EstadoPc> oyente) {
        this.conector = conector;
        this.fuente = fuente;
        this.ajustes = ajustes;
        this.dispositivo = dispositivo;
        this.oyente = oyente;
        this.opcionPedida = opcion;
        this.quiereSilenciar = silenciar;
        estado.dispositivo = dispositivo;
        estado.opcion = opcion;
        estado.kBps = ajustes.kBps(dispositivo.direccion);
        control.modo(true, FlujoEmisor.OBJETIVO_EN_VIVO_MS);
        hiloEnlace = new Thread(this::ejecutar, "ParlanteY-enlace");
        hiloVigia = new Thread(this::vigilar, "ParlanteY-vigia");
        hiloVigia.setDaemon(true);
    }

    public void iniciar() {
        hiloEnlace.start();
        hiloVigia.start();
    }

    // --- Pedidos de la ventana (cualquier hilo).

    public void enviar(boolean si) {
        quiereEnviar = si;
        despertar();
    }

    public void opcion(EstadoPc.Opcion o) {
        opcionPedida = o;
        despertar();
    }

    public void silenciar(boolean si) {
        quiereSilenciar = si;
        despertar();
    }

    public void probar() {
        pruebaPedida = true;
        despertar();
    }

    /** Desconecta: DETENER y BYE(0) si hay conexión; sin reintentos. */
    public void detener() {
        detenido = true;
        Transporte t = transporte;
        if (sesion == null && t != null) {
            t.cerrar(); // conectando o esperando el HELLO
        }
        conector.cancelar();
        despertar();
    }

    public void esperarFin(long ms) throws InterruptedException {
        hiloEnlace.join(ms);
    }

    public boolean vivo() {
        return hiloEnlace.isAlive();
    }

    public synchronized List<Resumen> resumenes() {
        List<Resumen> l = new ArrayList<>();
        for (Resumen r : resumenes) {
            l.add(r.copia());
        }
        return l;
    }

    private void despertar() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void publicar() {
        oyente.accept(estado.copia());
    }

    // --- Hilo de enlace.

    private void ejecutar() {
        int intento = 0;
        boolean huboHello = false;
        try {
            while (!detenido) {
                estado.enlace = huboHello ? EstadoPc.Enlace.RECONECTANDO : EstadoPc.Enlace.CONECTANDO;
                estado.intento = intento;
                publicar();
                Transporte t = null;
                Exception falla = null;
                try {
                    t = conector.conectar(dispositivo);
                    transporte = t;
                    if (detenido) {
                        break;
                    }
                    plazo(TIEMPO_HELLO_MS);
                    SesionEmisor s = new SesionEmisor(t.entrada(), t.salida());
                    Hello hello = s.esperarHello();
                    plazo(0);
                    sesion = s;
                    huboHello = true;
                    intento = 0;
                    conectado(s, hello);
                } catch (Exception e) {
                    falla = e;
                } finally {
                    plazo(0);
                    fuente.silenciar(false); // sin conexión el PC vuelve a sonar; se silencia de nuevo al reconectar
                    SesionEmisor s = sesion;
                    sesion = null;
                    transporte = null;
                    if (t != null) {
                        t.cerrar();
                    }
                    if (s != null) {
                        s.cerrar();
                    }
                    control.desconectar(); // la captura sigue; al reconectar se envía lo más reciente
                }
                if (detenido || falla == null) {
                    break;
                }
                String reintentar = clasificar(falla, huboHello);
                if (reintentar == null) {
                    break;
                }
                long espera = Math.min(1000L << Math.min(intento, 4), MAX_ESPERA_MS);
                intento++;
                estado.enlace = EstadoPc.Enlace.RECONECTANDO;
                estado.intento = intento;
                estado.causa = reintentar;
                publicar();
                esperar(espera);
            }
        } catch (Throwable e) {
            estado.error = "Error inesperado: " + e;
        } finally {
            terminarEnvio(null);
            fuente.detener();
            estado.enlace = EstadoPc.Enlace.INACTIVO;
            estado.silenciado = false;
            publicar();
        }
    }

    /** null si la sesión termina (y deja el error en el estado); si no, la causa para reintentar. */
    private String clasificar(Exception e, boolean huboHello) {
        if (e instanceof VersionIncompatibleException) {
            estado.error = "El receptor usa el protocolo v" + ((VersionIncompatibleException) e).versionRemota
                    + " y este programa el v" + Protocolo.VERSION + ". Instala la misma versión de ParlanteY en los dos.";
            return null;
        }
        if (e instanceof ByeRecibidoException) {
            int codigo = ((ByeRecibidoException) e).codigo;
            if (codigo == Protocolo.BYE_NORMAL) {
                estado.aviso = "El receptor se detuvo.";
            } else if (codigo == Protocolo.BYE_VERSION) {
                estado.error = "El receptor usa otra versión del protocolo. Actualiza los dos.";
            } else {
                estado.error = "El receptor cerró la sesión por un error de protocolo (BYE " + codigo + ").";
            }
            return null;
        }
        if (e instanceof ProtocoloException) {
            estado.error = "Error de protocolo: " + e.getMessage();
            return null;
        }
        if (e instanceof IOException) {
            if (huboHello) {
                return "se perdió la conexión";
            }
            estado.error = "No se pudo conectar con " + dispositivo.nombre + ". ¿Está abierto ParlanteY Receptor en el "
                    + "Galaxy Y y vinculado con este PC? (" + e.getMessage() + ")";
            return null;
        }
        estado.error = "Error inesperado: " + e;
        return null;
    }

    private void conectado(SesionEmisor s, Hello hello) throws IOException {
        estado.enlace = EstadoPc.Enlace.CONECTADO;
        estado.receptor = hello.nombre;
        estado.error = null;
        estado.causa = null;
        cortesPrevios += cortesConexion;
        cortesConexion = 0;
        cortesBase = 0;
        if (resumen != null && cola != null) {
            resumen.reconexiones++;
        }
        control.conectar(s.escritor(), s::estado);
        s.setAudioActivo(cola != null);
        publicar();
        if (!pruebaAutomaticaHecha && Double.isNaN(estado.kBps)) {
            pruebaAutomaticaHecha = true; // primera vez con este receptor: la prueba decide el preset Auto
            pruebaPedida = true;
        }
        long proximoPing = 0;
        long estadosVistos = -1;
        while (true) {
            s.comprobar();
            if (detenido) {
                despedirse(s);
                return;
            }
            aplicarPedidos(s);
            long ahora = System.nanoTime();
            if (ahora - proximoPing >= 0) {
                s.enviarPing();
                proximoPing = ahora + SesionEmisor.INTERVALO_PING_NS;
                long rtt = s.ultimoRttNs();
                if (rtt > 0) {
                    estado.rttMs = rtt / 1e6;
                }
            }
            long espera = ESPERA_INACTIVO_MS;
            if (cola != null) {
                espera = control.paso(ahora);
                if (espera == FlujoEmisor.FIN) {
                    if (control.abriendo()) {
                        espera = ESPERA_ABRIENDO_MS;
                    } else {
                        // La cola de la captura se cerró: Windows la detuvo o se cortó.
                        String motivo = fuente.error();
                        estado.aviso = "Se dejó de enviar el audio del PC" + (motivo != null ? ": " + motivo : ".");
                        quiereEnviar = false;
                        terminarEnvio(s);
                        espera = 0;
                    }
                }
            }
            long n = s.estadosRecibidos();
            if (n != estadosVistos) {
                estadosVistos = n;
                alRecibirEstado(s);
            }
            if (espera > 0) {
                long hastaPing = (proximoPing - System.nanoTime()) / 1000000L;
                s.esperarEstadoTras(estadosVistos, Math.min(espera, Math.max(1L, hastaPing)));
            }
        }
    }

    /** Aplica lo que pidió la ventana. */
    private void aplicarPedidos(SesionEmisor s) throws IOException {
        if (pruebaPedida) {
            pruebaPedida = false;
            if (cola == null) {
                probar(s);
            }
        }
        if (quiereEnviar && cola == null) {
            empezarEnvio(s);
        } else if (!quiereEnviar && cola != null) {
            terminarEnvio(s);
        }
        EstadoPc.Opcion o = opcionPedida;
        if (o != opcionAplicada) {
            opcionAplicada = o;
            estado.opcion = o;
            Preset p = o.resolver(estado.kBps);
            if (cola != null) {
                control.cambiarPreset(p);
            } else {
                control.preset(p);
            }
            estado.preset = p;
            publicar();
        }
        // Se silencia recién con señal (algo suena): así se puede comprobar que la captura sigue.
        long ahora = System.nanoTime();
        boolean conSenal = ultimaSenalNs != 0 && ahora - ultimaSenalNs < 600000000L;
        boolean silenciar = quiereSilenciar && cola != null && !silencioNoSirve;
        if (silenciar && !fuente.silenciado() && conSenal) {
            fuente.silenciar(true);
            silenciadoDesdeNs = ahora;
        } else if (!silenciar && fuente.silenciado()) {
            fuente.silenciar(false);
        }
    }

    private void probar(SesionEmisor s) throws IOException {
        estado.midiendo = true;
        publicar();
        plazo(TIEMPO_PRUEBA_MS);
        ResultadoPrueba r = s.probarEnlace(PINGS_PRUEBA, DURACION_PRUEBA_MS, SesionEmisor.LARGO_TRAMA_PRUEBA);
        plazo(0);
        estado.midiendo = false;
        estado.kBps = r.kBpsReceptor();
        estado.rttMs = r.rttPromMs();
        ajustes.kBps(dispositivo.direccion, estado.kBps);
        Preset p = estado.opcion.resolver(estado.kBps);
        control.preset(p);
        estado.preset = p;
        publicar();
    }

    private void empezarEnvio(SesionEmisor s) {
        ColaCaptura c;
        try {
            c = fuente.iniciar();
        } catch (IOException e) {
            quiereEnviar = false;
            estado.aviso = "No se pudo capturar el audio del PC: " + e.getMessage();
            publicar();
            return;
        }
        cola = c;
        ultimaSenalNs = 0;
        inicioEnvioNs = System.nanoTime();
        control.reiniciarErrores();
        control.cola(new FabricaCaptura(c));
        Preset p = estado.opcion.resolver(estado.kBps);
        control.preset(p);
        control.reproducir(0, 0);
        s.setAudioActivo(true);
        cortesPrevios = 0;
        cortesConexion = 0;
        Estado e = s.estado();
        cortesBase = e != null ? e.cortes : 0;
        estado.enviando = true;
        estado.preset = p;
        estado.formatoCaptura = fuente.descripcion();
        estado.aviso = null;
        long ahora = System.currentTimeMillis();
        Resumen r = new Resumen();
        r.inicio = LocalDateTime.now().format(HORA);
        r.desdeMs = ahora;
        r.hastaMs = ahora;
        r.opcion = estado.opcion.etiqueta;
        synchronized (this) {
            resumen = r;
            resumenes.add(r);
        }
        publicar();
    }

    /** Deja de enviar (DETENER si hay conexión) y de capturar. */
    private void terminarEnvio(SesionEmisor s) {
        if (cola == null) {
            return;
        }
        control.detener();
        if (s != null) {
            try {
                control.paso(System.nanoTime());
            } catch (IOException ignorada) {
                // la conexión se corta igual
            }
            s.setAudioActivo(false);
        }
        fuente.detener();
        cola = null;
        estado.enviando = false;
        estado.silenciado = false;
        estado.silencio = false;
        synchronized (this) {
            if (resumen != null) {
                resumen.terminado = true;
                resumen.hastaMs = System.currentTimeMillis();
                resumen = null;
            }
        }
        publicar();
    }

    private void alRecibirEstado(SesionEmisor s) {
        Estado e = s.estado();
        if (e == null) {
            return;
        }
        cortesConexion = Math.max(0, e.cortes - cortesBase);
        estado.cortes = cortesPrevios + cortesConexion;
        estado.bufferMs = e.bufferMs;
        estado.sonando = e.reproduciendo;
        ColaCaptura c = cola;
        long ahora = System.nanoTime();
        if (c != null) {
            if (c.picoYReiniciar() > UMBRAL_SILENCIO) {
                ultimaSenalNs = ahora;
            }
            estado.silencio = ahora - Math.max(ultimaSenalNs, inicioEnvioNs) >= AVISO_SILENCIO_NS;
            estado.latenciaMs = control.flujo().nivelEstimadoMs() + c.msPendientes();
            estado.descartadosMs = c.msDescartados();
            Config cfg = control.flujo().config();
            if (cfg != null) {
                estado.formatoEnvio = (cfg.sampleRate % 1000 == 0 ? cfg.sampleRate / 1000 + "" : cfg.sampleRate / 100 / 10.0 + "")
                        + " kHz " + (cfg.canales == 2 ? "estéreo" : "mono");
            }
            // ¿Silenciar el PC también calló la captura? Entonces no sirve en este PC: se quita.
            // (Lo capturado justo antes de silenciar todavía puede llegar: margen de 0,5 s.)
            if (fuente.silenciado() && ahora - silenciadoDesdeNs > PRUEBA_SILENCIO_NS
                    && ultimaSenalNs < silenciadoDesdeNs + MARGEN_SILENCIO_NS) {
                silencioNoSirve = true;
                fuente.silenciar(false);
                estado.aviso = "En este PC, silenciar la salida también calla la captura: se dejó de silenciar. "
                        + "Baja el volumen de los parlantes del PC en su lugar.";
            }
            actualizarResumen(e);
        }
        estado.silenciado = fuente.silenciado();
        publicar();
    }

    private synchronized void actualizarResumen(Estado e) {
        Resumen r = resumen;
        if (r == null) {
            return;
        }
        r.hastaMs = System.currentTimeMillis();
        r.cortes = estado.cortes;
        if (estado.preset != null && !r.presets.contains(estado.preset.nombre)) {
            r.presets = r.presets.isEmpty() ? estado.preset.nombre : r.presets + ", " + estado.preset.nombre;
        }
        if (estado.formatoEnvio != null) {
            r.formato = estado.formatoEnvio;
        }
        if (e.reproduciendo) {
            r.bufferMin = Math.min(r.bufferMin, e.bufferMs);
            r.bufferMax = Math.max(r.bufferMax, e.bufferMs);
            r.bufferSuma += e.bufferMs;
            r.latenciaMin = Math.min(r.latenciaMin, estado.latenciaMs);
            r.latenciaMax = Math.max(r.latenciaMax, estado.latenciaMs);
            r.latenciaSuma += estado.latenciaMs;
            r.muestras++;
        }
        r.descartadosMs = estado.descartadosMs;
    }

    private void despedirse(SesionEmisor s) {
        try {
            if (cola != null) {
                control.detener();
                control.paso(System.nanoTime());
            }
            s.despedir(Protocolo.BYE_NORMAL);
            Thread.sleep(150); // en RFCOMM de Windows, cerrar enseguida a veces pierde los últimos bytes (el BYE)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignorada) {
            // se cierra igual
        }
        terminarEnvio(null);
    }

    private void plazo(long ms) {
        plazoNs = ms == 0 ? 0 : System.nanoTime() + ms * 1000000L;
    }

    private void esperar(long ms) {
        long fin = System.currentTimeMillis() + ms;
        synchronized (monitor) {
            while (!detenido) {
                long resto = fin - System.currentTimeMillis();
                if (resto <= 0) {
                    return;
                }
                try {
                    monitor.wait(resto);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // --- Hilo vigía.

    private void vigilar() {
        while (hiloEnlace.isAlive() || !detenido) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            if (!hiloEnlace.isAlive() && detenido) {
                return;
            }
            Transporte t = transporte;
            if (t == null) {
                continue;
            }
            long p = plazoNs;
            long ahora = System.nanoTime();
            SesionEmisor s = sesion;
            if (p != 0 && ahora - p > 0) {
                t.cerrar();
            } else if (s != null && s.vencida(ahora)) {
                t.cerrar(); // 4 s sin tramas del receptor
            } else if (detenido && s == null) {
                t.cerrar();
            }
        }
    }

    /** La captura como cola de una pista en vivo: al (re)abrirse, lo viejo se descarta. */
    static final class FabricaCaptura implements ControlCola.Fabrica {
        static final Track TRACK = new Track("Audio del PC", "", 0);
        private final ColaCaptura cola;

        FabricaCaptura(ColaCaptura cola) {
            this.cola = cola;
        }

        @Override
        public int cantidad() {
            return 1;
        }

        @Override
        public ControlCola.Apertura abrir(int indice, long desdeMs) {
            cola.vaciar();
            return new ControlCola.Apertura() {
                @Override
                public boolean lista() {
                    return true;
                }

                @Override
                public Fuente fuente() {
                    return cola;
                }

                @Override
                public Track track() {
                    return TRACK;
                }

                @Override
                public String error() {
                    return null;
                }

                @Override
                public void cerrar() {
                }
            };
        }
    }
}
