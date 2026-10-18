package cl.parlantey.core;

/**
 * Reproducción en el receptor: saca bloques de {@link ColaBloques}, los decodifica y los escribe en
 * una {@link Salida}. Lo usa un solo hilo (el de audio), que llama a {@link #paso} en bucle y espera
 * en la cola lo que el paso devuelve. Ningún paso bloquea ni asigna memoria.
 *
 * <p>Estados: SIN_CONFIG → LLENANDO → SONANDO ⇄ RELLENANDO, más la pausa aparte.
 * <ul>
 * <li>LLENANDO: no escribe; espera a tener el umbral de bloques reales en cola y entonces suena.</li>
 * <li>SONANDO: escribe bloques reales. Si la cola se vacía y en la salida queda menos de un bloque
 * real, cuenta un corte y pasa a RELLENANDO.</li>
 * <li>RELLENANDO: escribe silencio de a un bloque (la salida sigue sonando) hasta volver al umbral.</li>
 * </ul>
 * VACIAR, DETENER, un CONFIG nuevo, REANUDAR y el arranque no cuentan como corte.
 */
public final class Reproductor {

    public static final int SIN_CONFIG = 0;
    public static final int LLENANDO = 1;
    public static final int SONANDO = 2;
    public static final int RELLENANDO = 3;

    /** Espera cuando no hay nada que hacer (sin CONFIG o en pausa). */
    static final long ESPERA_INACTIVO_MS = 50;
    /** Espera tras una escritura fallida. */
    static final long ESPERA_ERROR_MS = 10;
    private static final int MAX_ERRORES_SEGUIDOS = 5;
    private static final double FRACCION_ARRANQUE = 0.7;
    /** Si la salida no se pudo abrir, se reintenta cada 1 s. */
    private static final long REINTENTO_ABRIR_NS = 1000000000L;
    /**
     * Salida llena cuya posición no avanza en 1,5 s (o retrocede sin que la vaciemos): AudioTrack
     * quedó trabado o se recreó solo (p. ej. murió mediaserver). Se reabre.
     */
    static final long SALIDA_TRABADA_NS = 1500000000L;
    /** Tras 3 reaperturas seguidas sin que suene, 5 s sin vigilar antes de intentar de nuevo. */
    private static final int MAX_REAPERTURAS_SEGUIDAS = 3;
    private static final long PAUSA_TRAS_REAPERTURAS_NS = 5000000000L;

    /** Muestras (todos los canales) que caben en un bloque decodificado: los CONFIG con más se rechazan. */
    public static final int MAX_MUESTRAS_BLOQUE = Protocolo.MAX_CANALES * Protocolo.MUESTRAS_POR_BLOQUE;

    private final ColaBloques cola;
    private final Salida salida;
    private final long capacidadMs;
    private final short[] pcm;
    private final short[] silencio;

    // --- Estado del hilo de audio.
    private int estado = SIN_CONFIG;
    private int generacion = Integer.MIN_VALUE;
    private Config config;
    private boolean abierta;
    private boolean pausado;
    private int capacidad;
    /** Frames escritos a la salida, en la escala de su posición (32 bits, con vuelta). */
    private int escritos;
    /** Valor de {@link #escritos} al terminar el último frame real (el silencio no cuenta). */
    private int finReal;
    /**
     * Tras un corte, el audio real se escribe detrás del silencio que quedó en la salida: el
     * silencio termina en {@link #inicioReal} y no cuenta como pendiente real hasta que suene.
     */
    private int inicioReal;
    private boolean silencioDelante;
    private int umbralBloques;
    private long objetivoUsado = -1;

    /** Lo que falta escribir: un bloque real ({@code actual != null}) o silencio. */
    private ColaBloques.Bloque actual;
    private short[] porEscribir;
    private int desde;
    private int frames;
    private int erroresSeguidos;
    private long ultimaEscrituraNs;
    /** Frames reales escritos desde que empezó a sonar (saturado en la capacidad de la salida). */
    private int framesDesdeArranque;
    private long ultimoIntentoAbrirNs;
    /** Última posición vista de la salida y desde cuándo no cambia (detección de salida trabada). */
    private int ultimaPosicion;
    private long posicionDesdeNs;
    private boolean vigilarPosicion;
    /** Reaperturas por salida trabada sin que la posición avance entre una y otra. */
    private int reaperturasSeguidas;
    private long sinVigilarHastaNs;

    // --- Pedidos de otros hilos.
    private volatile boolean pausaPedida;
    private volatile boolean reinicioMetricasPedido;

    // --- Publicado para el hilo de STATE y la UI.
    private volatile Config configPublicada;
    private volatile int framesPendientes;
    private volatile int estadoPublicado = SIN_CONFIG;
    private volatile boolean reproduciendo;
    private volatile boolean finalizado;
    private volatile int cortes;
    private volatile int subcortes;
    private volatile int erroresSalida;
    private volatile int minPendienteFrames = Integer.MAX_VALUE;
    private volatile long decodNsTotal;
    private volatile int decodBloques;
    private volatile long decodNsMax;
    private volatile long intervaloMaxNs;

    /**
     * @param capacidadMs tope de {@link #bufferMs()} (capacidadBufferMs del HELLO)
     */
    public Reproductor(ColaBloques cola, Salida salida, long capacidadMs) {
        this.cola = cola;
        this.salida = salida;
        this.capacidadMs = capacidadMs;
        this.pcm = new short[MAX_MUESTRAS_BLOQUE];
        this.silencio = new short[pcm.length];
    }

    // --- Pedidos desde el hilo de red.

    /** PAUSA (true) o REANUDAR (false). Gana el último pedido. */
    public void pedirPausa(boolean pausa) {
        pausaPedida = pausa;
        cola.despertar();
    }

    /**
     * Pone en cero cortes y métricas (al empezar una conexión). Lo aplica el hilo de audio en su
     * próximo paso; mientras tanto {@link #reinicioPendiente()} es true.
     */
    public void reiniciarMetricas() {
        reinicioMetricasPedido = true;
        cola.despertar();
    }

    /** true si {@link #reiniciarMetricas} todavía no se aplicó (las métricas son de antes). */
    public boolean reinicioPendiente() {
        return reinicioMetricasPedido;
    }

    // --- Hilo de audio.

    /**
     * Avanza la reproducción. Hace como mucho una escritura de un bloque. Devuelve cuántos ms
     * conviene esperar (en la cola, que despierta antes si llega algo) antes del próximo paso.
     */
    public long paso(long ahoraNs) {
        if (reinicioMetricasPedido) {
            cortes = 0;
            subcortes = 0;
            erroresSalida = 0;
            minPendienteFrames = Integer.MAX_VALUE;
            decodNsTotal = 0;
            decodBloques = 0;
            decodNsMax = 0;
            intervaloMaxNs = 0;
            reinicioMetricasPedido = false; // después de poner en cero: el STATE espera a esto
        }

        int gen;
        Config cfg;
        synchronized (cola) {
            gen = cola.generacion();
            cfg = cola.config();
        }
        if (gen != generacion) {
            ultimoIntentoAbrirNs = ahoraNs;
            aplicarGeneracion(gen, cfg);
        } else if (cfg != null && config == null && ahoraNs - ultimoIntentoAbrirNs >= REINTENTO_ABRIR_NS) {
            ultimoIntentoAbrirNs = ahoraNs; // la salida no se pudo abrir: otro intento
            aplicarGeneracion(gen, cfg);
        } else if (cfg != null && config != null && cfg.bufferObjetivoMs != objetivoUsado) {
            config = cfg;
            calcularUmbral();
        }

        aplicarPausa();

        long espera;
        if (estado == SIN_CONFIG || pausado) {
            espera = ESPERA_INACTIVO_MS;
        } else if (estado == LLENANDO) {
            espera = pasoLlenando();
        } else {
            espera = pasoSonando(ahoraNs);
        }
        publicar();
        return espera;
    }

    /** Libera la salida (al terminar el servicio). */
    public void cerrar() {
        soltarActual();
        if (abierta) {
            salida.detener();
            salida.cerrar();
            abierta = false;
        }
        config = null;
        estado = SIN_CONFIG;
        publicar();
    }

    private void aplicarGeneracion(int gen, Config cfg) {
        generacion = gen;
        soltarActual();
        if (cfg == null) { // DETENER o fin de la sesión
            if (abierta) {
                salida.detener();
                salida.cerrar();
                abierta = false;
            }
            config = null;
            estado = SIN_CONFIG;
            return;
        }
        if (abierta && config != null && mismoFormato(config, cfg)) {
            salida.vaciar(); // VACIAR o CONFIG con el mismo formato tras DETENER/VACIAR
        } else if (!reabrir(cfg)) {
            return;
        }
        config = cfg;
        reiniciarPosicion();
        estado = LLENANDO;
        calcularUmbral();
    }

    /** Cierra la salida si estaba abierta y la abre con el formato de {@code cfg}. */
    private boolean reabrir(Config cfg) {
        if (abierta) {
            salida.detener();
            salida.cerrar();
            abierta = false;
        }
        if (!salida.abrir(cfg.sampleRate, cfg.canales)) {
            erroresSalida++;
            config = null;
            estado = SIN_CONFIG;
            return false;
        }
        abierta = true;
        capacidad = salida.capacidadFrames();
        return true;
    }

    private static boolean mismoFormato(Config a, Config b) {
        return a.sampleRate == b.sampleRate && a.canales == b.canales && a.muestrasPorBloque == b.muestrasPorBloque;
    }

    /** Umbral de arranque: 70 % del objetivo, al menos el buffer de la salida + 2 bloques. */
    private void calcularUmbral() {
        objetivoUsado = config.bufferObjetivoMs;
        long porObjetivo = bloquesPara(FRACCION_ARRANQUE * config.bufferObjetivoMs);
        long minimo = (capacidad + config.muestrasPorBloque - 1) / config.muestrasPorBloque + 2;
        long maximo = cola.capacidadBloques() - 2;
        umbralBloques = (int) Math.max(1, Math.min(Math.max(porObjetivo, minimo), maximo));
    }

    private long bloquesPara(double ms) {
        double msPorBloque = 1000.0 * config.muestrasPorBloque / config.sampleRate;
        return (long) Math.ceil(ms / msPorBloque);
    }

    private void aplicarPausa() {
        boolean pedida = pausaPedida;
        if (pedida == pausado) {
            return;
        }
        if (!pedida && cola.generacion() != generacion) {
            return; // llegó un VACIAR/DETENER: aplicarlo primero (no hacer sonar la pista vieja)
        }
        pausado = pedida;
        if (!abierta || (estado != SONANDO && estado != RELLENANDO)) {
            return;
        }
        if (pausado) {
            salida.pausar();
            ultimaEscrituraNs = 0;
        } else if ((cola.enCola() < umbralBloques && pendienteReal() < config.muestrasPorBloque)
                || cola.enCola() * config.muestrasPorBloque + pendienteTotal() < capacidad) {
            // REANUDAR sin datos suficientes: vuelve a llenar, sin contar corte. En 2.3, una pista
            // en pausa sale de las activas de AudioFlinger y al reanudarla no suena hasta llenarse
            // otra vez: sin lo necesario para llenarla, se quedaría muda.
            if (estado == RELLENANDO) {
                marcarSilencioDelante(); // lo que quedó en la salida es silencio de relleno
            }
            estado = LLENANDO;
        } else {
            arrancar(estado);
        }
    }

    private long pasoLlenando() {
        int enCola = cola.enCola();
        if (enCola < umbralBloques && (enCola == 0 || !cola.finPedido(generacion))) {
            return 20;
        }
        // Umbral alcanzado, o FIN: no viene más, así que suena lo que hay aunque sea poco.
        arrancar(SONANDO);
        return 0;
    }

    /** FIN y ya sonó todo lo real: vaciar la salida (solo queda silencio) y esperar audio nuevo. */
    private long terminarFin() {
        soltarActual();
        salida.vaciar();
        reiniciarPosicion();
        estado = LLENANDO;
        return ESPERA_INACTIVO_MS;
    }

    /** Empieza (o retoma) a sonar. Las métricas de continuidad parten de cero. */
    private void arrancar(int nuevoEstado) {
        estado = nuevoEstado;
        framesDesdeArranque = 0;
        ultimaEscrituraNs = 0;
        vigilarPosicion = false; // la salida tarda en arrancar: se empieza a vigilar al llenarla
        salida.reproducir();
    }

    /** El audio real que se escriba desde ahora queda detrás del silencio ya escrito. */
    private void marcarSilencioDelante() {
        soltarActual();
        inicioReal = escritos;
        silencioDelante = true;
    }

    private long pasoSonando(long ahoraNs) {
        int bloque = config.muestrasPorBloque;
        if (estado == RELLENANDO && cola.finPedido(generacion)) {
            // El flujo terminó durante un corte: sonar lo que quedó en la cola, o terminar.
            if (cola.enCola() == 0) {
                return terminarFin();
            }
            marcarSilencioDelante();
            estado = SONANDO;
        }
        if (estado == RELLENANDO && cola.enCola() >= umbralBloques) {
            marcarSilencioDelante(); // y el resto del bloque de silencio en curso no hace falta
            estado = SONANDO;
            framesDesdeArranque = 0; // lo pendiente es silencio: como un arranque
        }
        if (porEscribir == null) {
            if (estado == SONANDO) {
                ColaBloques.Bloque b = cola.sacar(generacion);
                if (b == null && cola.generacion() != generacion) {
                    return 0; // llegó un VACIAR/CONFIG/DETENER durante este paso: aplicarlo primero
                }
                if (b == null) {
                    int pendiente = pendienteReal();
                    if (cola.finPedido(generacion)) {
                        if (pendiente <= 0) {
                            return terminarFin();
                        }
                        // FIN: mientras suena lo que queda, silencio detrás (sin corte). La salida
                        // sigue llena y, si no se había llenado nunca, así arranca (en 2.3 no suena
                        // hasta llenarse).
                    } else if (pendiente > bloque) {
                        // Aún suena audio real: esperar a que quede ~1 bloque antes de declarar corte.
                        return Math.max(1, config.msParaFrames(pendiente - bloque));
                    } else {
                        cortes++;
                        estado = RELLENANDO;
                    }
                } else {
                    if (framesDesdeArranque >= capacidad) { // la salida ya se llenó una vez
                        int pendiente = pendienteReal();
                        if (pendiente <= bloque) {
                            subcortes++;
                        }
                        if (pendiente < minPendienteFrames) {
                            minPendienteFrames = pendiente;
                        }
                    }
                    decodificar(b);
                }
            }
            if (porEscribir == null) { // RELLENANDO o FIN: un bloque de silencio
                porEscribir = silencio;
                desde = 0;
                frames = bloque;
            }
        }
        return escribirPendiente(ahoraNs);
    }

    private void decodificar(ColaBloques.Bloque b) {
        actual = b; // antes de decodificar: pase lo que pase, el bloque vuelve al pool
        long t0 = System.nanoTime();
        ImaAdpcm.decodificarBloque(b.datos, 0, config.canales, config.muestrasPorBloque, pcm, 0);
        long d = System.nanoTime() - t0;
        decodNsTotal += d;
        decodBloques++;
        if (d > decodNsMax) {
            decodNsMax = d;
        }
        porEscribir = pcm;
        desde = 0;
        frames = config.muestrasPorBloque;
    }

    private long escribirPendiente(long ahoraNs) {
        int libre = capacidad - pendienteTotal();
        int resto = frames - desde;
        if (libre <= 0) {
            if (salidaTrabada(ahoraNs)) {
                erroresSalida++;
                if (++reaperturasSeguidas >= MAX_REAPERTURAS_SEGUIDAS) {
                    // Reabrir no la arregla: dejar pasar un rato antes de otro intento.
                    reaperturasSeguidas = 0;
                    sinVigilarHastaNs = ahoraNs + PAUSA_TRAS_REAPERTURAS_NS;
                }
                soltarActual();
                if (reabrir(config)) {
                    reiniciarPosicion();
                    estado = LLENANDO;
                }
                return 0;
            }
            // La salida está llena: esperar a que haya lugar para lo que falta (sin bloquear).
            return Math.max(1, config.msParaFrames(Math.min(resto, config.muestrasPorBloque / 4)));
        }
        int n = salida.escribir(porEscribir, desde * config.canales, Math.min(libre, resto));
        if (n <= 0) {
            if (++erroresSeguidos >= MAX_ERRORES_SEGUIDOS) {
                erroresSeguidos = 0;
                erroresSalida++;
                soltarActual();
                if (reabrir(config)) {
                    reiniciarPosicion();
                    estado = LLENANDO;
                }
            }
            return ESPERA_ERROR_MS;
        }
        erroresSeguidos = 0;
        if (ultimaEscrituraNs != 0 && ahoraNs - ultimaEscrituraNs > intervaloMaxNs) {
            intervaloMaxNs = ahoraNs - ultimaEscrituraNs;
        }
        ultimaEscrituraNs = ahoraNs;
        escritos += n;
        if (porEscribir != silencio) {
            finReal = escritos;
            if (framesDesdeArranque < capacidad) {
                framesDesdeArranque += n;
            }
        }
        desde += n;
        if (desde >= frames) {
            soltarActual();
        }
        return 0;
    }

    /** Devuelve el bloque en curso al pool y olvida lo que faltaba escribir. */
    private void soltarActual() {
        if (actual != null) {
            cola.devolver(actual);
            actual = null;
        }
        porEscribir = null;
        desde = 0;
        frames = 0;
    }

    /**
     * Tras abrir o vaciar la salida no queda nada pendiente: los contadores parten de su posición
     * (0 en AudioTrack, pero así la aritmética no depende de eso).
     */
    private void reiniciarPosicion() {
        escritos = salida.framesReproducidos();
        finReal = escritos;
        silencioDelante = false;
        vigilarPosicion = false;
    }

    /**
     * Con la salida llena (según lo escrito) y sonando: true si su posición no avanza hace
     * {@link #SALIDA_TRABADA_NS} o si retrocedió. Una salida que todavía se está llenando no
     * cuenta: AudioFlinger no la hace sonar hasta llenarla.
     */
    private boolean salidaTrabada(long ahoraNs) {
        if (sinVigilarHastaNs != 0) {
            if (ahoraNs - sinVigilarHastaNs < 0) {
                return false;
            }
            sinVigilarHastaNs = 0;
            vigilarPosicion = false;
        }
        int posicion = salida.framesReproducidos();
        if (!vigilarPosicion) {
            vigilarPosicion = true;
            ultimaPosicion = posicion;
            posicionDesdeNs = ahoraNs;
            return false;
        }
        if (posicion != ultimaPosicion) {
            boolean atras = posicion - ultimaPosicion < 0;
            ultimaPosicion = posicion;
            posicionDesdeNs = ahoraNs;
            if (!atras) {
                reaperturasSeguidas = 0; // suena
            }
            return atras;
        }
        return ahoraNs - posicionDesdeNs > SALIDA_TRABADA_NS;
    }

    /** Frames escritos que la salida aún no reproduce (32 bits con vuelta). */
    private int pendienteTotal() {
        return limitar(escritos - salida.framesReproducidos());
    }

    /** Como {@link #pendienteTotal()}, pero solo audio real: el silencio de relleno no cuenta. */
    private int pendienteReal() {
        if (!abierta) {
            return 0;
        }
        int posicion = salida.framesReproducidos();
        if (finReal - posicion < 0) {
            finReal = posicion; // ya sonó: que la resta no dé la vuelta tras 2³¹ frames
        }
        int pendiente = limitar(finReal - posicion);
        if (silencioDelante) {
            int silencio = inicioReal - posicion;
            if (silencio <= 0) {
                silencioDelante = false; // ya sonó: no seguir restando (la resta daría la vuelta)
            } else {
                pendiente = Math.max(0, pendiente - Math.min(silencio, capacidad));
            }
        }
        return pendiente;
    }

    private int limitar(int frames) {
        return frames < 0 ? 0 : Math.min(frames, capacidad);
    }

    private void publicar() {
        configPublicada = config;
        int pendiente = pendienteReal();
        if (actual != null) {
            pendiente += frames - desde;
        }
        framesPendientes = pendiente;
        estadoPublicado = estado;
        reproduciendo = estado == SONANDO && !pausado;
        finalizado = estado == LLENANDO && cola.finPedido(generacion) && cola.enCola() == 0;
    }

    // --- Lecturas para el hilo de STATE y la UI (cualquier hilo).

    /**
     * bufferMs de STATE: bloques en cola + audio real pendiente en la salida, en ms, acotado a
     * [0, capacidadMs]. Para que vaya junto con {@code ultimoSeq}, llamarlo con el monitor de la cola.
     */
    public long bufferMs() {
        Config c = configPublicada;
        if (c == null) {
            c = cola.config(); // la salida no se pudo abrir todavía: igual cuenta lo encolado
        }
        if (c == null) {
            return 0;
        }
        long ms = c.msParaBloques(cola.enCola()) + c.msParaFrames(framesPendientes);
        return Math.max(0, Math.min(ms, capacidadMs));
    }

    public int estado() {
        return estadoPublicado;
    }

    public boolean reproduciendo() {
        return reproduciendo;
    }

    /** Llegó FIN y ya sonó todo (para la UI: «Fin de la cola»). */
    public boolean finalizado() {
        return finalizado;
    }

    public boolean pausado() {
        return pausaPedida;
    }

    public Config config() {
        return configPublicada;
    }

    public int cortes() {
        return cortes;
    }

    /** Bloques reales que se escribieron con menos de un bloque real pendiente en la salida. */
    public int subcortes() {
        return subcortes;
    }

    public int erroresSalida() {
        return erroresSalida;
    }

    /** Mínimo de frames reales pendientes en la salida antes de escribir un bloque. */
    public int minPendienteFrames() {
        return minPendienteFrames;
    }

    /** Decodificación promedio por bloque, en µs (0 si todavía no decodificó). */
    public long decodPromedioUs() {
        int n = decodBloques;
        return n == 0 ? 0 : decodNsTotal / n / 1000;
    }

    public long decodMaximoUs() {
        return decodNsMax / 1000;
    }

    public long intervaloMaximoMs() {
        return intervaloMaxNs / 1000000L;
    }
}
