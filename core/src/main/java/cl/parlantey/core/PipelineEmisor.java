package cl.parlantey.core;

import java.io.IOException;

/**
 * Pipeline del emisor: PCM16 de una {@link Fuente} → downmix y decimación según el {@link Preset}
 * → {@link CodificadorIma} → tramas AUDIO, regulado por el control de flujo con STATE.
 *
 * <p>Lo usa un solo hilo, que llama a {@link #paso} en bucle y escribe todo lo de la conexión en
 * orden (CONFIG, TRACK, CONTROL y AUDIO). {@link #pedirDetener} y {@link #pedirVaciar} se pueden
 * llamar desde cualquier hilo: se aplican en el próximo paso. La fuente sobrevive a las
 * reconexiones: tras {@link #conectar} se vuelven a enviar CONFIG y TRACK, y el seq sigue al último
 * AUDIO de esa conexión (0 en una conexión nueva).
 *
 * <p><b>Control de flujo</b> (PROTOCOLO.md): {@code nivel = STATE.bufferMs + Seq.distancia(seqEnviado,
 * STATE.ultimoSeq) × ms por bloque}; se envía mientras {@code nivel < bufferObjetivoMs}. Tras un
 * corte (inicio del pipeline en la conexión, o VACIAR), el STATE se ignora hasta que informe un
 * {@code ultimoSeq} enviado después del corte: hasta entonces el nivel es todo lo enviado desde el
 * corte. No asigna memoria por bloque.
 */
public final class PipelineEmisor {

    /** PCM16 entrelazado con tasa y canales fijos. */
    public interface Fuente {
        int tasa();

        int canales();

        /**
         * Escribe hasta {@code frames} frames en {@code destino} desde el frame {@code desdeFrame}
         * ({@code destino[(desdeFrame + i) * canales + c]}). Devuelve los frames escritos, 0 si por
         * ahora no hay datos, o {@link #FIN_FUENTE} si la fuente terminó. No debe bloquear.
         */
        int leer(short[] destino, int desdeFrame, int frames);
    }

    /** Último STATE de la conexión actual (null si todavía no llega ninguno). */
    public interface Estados {
        Estado ultimo();
    }

    public static final int FIN_FUENTE = -1;
    /** Lo que devuelve {@link #paso} cuando ya no hay nada más que enviar. */
    public static final long FIN = -1;
    /** Buffer objetivo para archivos y el tono de prueba (CLAUDE.md). */
    public static final long OBJETIVO_ARCHIVOS_MS = 2000;

    /** Nivel ≥ objetivo: esperar un STATE nuevo (cada 250 ms) o hasta este tiempo. */
    static final long ESPERA_LLENO_MS = 50;
    static final long ESPERA_SIN_DATOS_MS = 5;

    private final Fuente fuente;
    private final Preset preset;
    private final Track track;
    private final Config config;
    private final int canalesFuente;
    private final int canalesSalida;
    private final int etapas;
    private final DecimadorX2[] decimadores;
    private final CodificadorIma codificador;
    private final short[] entrada;
    private final short[] bloquePcm;
    private final byte[] bloqueAdpcm;
    private final int bytesBloque;

    /** Frames de entrada que sobraron (menos de 2^etapas), al inicio de {@link #entrada}. */
    private int arrastre;
    /** Frames ya procesados en {@link #bloquePcm}. */
    private int llenos;
    private boolean fuenteTerminada;
    private boolean terminado;

    // --- Conexión actual.
    private EscritorTramas escritor;
    private Estados estados;
    private boolean configEnviado;
    private long seqEnviado;
    /** Primer seq enviado después del último corte (inicio del pipeline en la conexión, o VACIAR). */
    private long seqCorte;

    private volatile boolean detenerPedido;
    private volatile boolean vaciarPedido;
    private volatile long nivelPublicadoMs;
    private volatile long bloquesEnviados;

    /**
     * @param track      se envía tras cada CONFIG; null para no enviar TRACK
     * @param objetivoMs bufferObjetivoMs del CONFIG y del control de flujo
     */
    public PipelineEmisor(Fuente fuente, Preset preset, Track track, long objetivoMs) {
        this.fuente = fuente;
        this.preset = preset;
        this.track = track;
        this.canalesFuente = fuente.canales();
        if (canalesFuente < 1 || canalesFuente > Downmix.MAX_CANALES_ENTRADA) {
            throw new IllegalArgumentException("canales de la fuente fuera de rango: " + canalesFuente);
        }
        int tasa = fuente.tasa();
        this.etapas = preset.etapas(tasa);
        this.canalesSalida = preset.canalesSalida(canalesFuente);
        this.config = Config.imaAdpcm(tasa >> etapas, canalesSalida, objetivoMs); // valida la tasa
        this.decimadores = new DecimadorX2[etapas];
        for (int i = 0; i < etapas; i++) {
            decimadores[i] = new DecimadorX2(canalesSalida);
        }
        int mpb = config.muestrasPorBloque;
        this.codificador = new CodificadorIma(canalesSalida, mpb);
        this.entrada = new short[(mpb << etapas) * canalesFuente];
        this.bloquePcm = new short[mpb * canalesSalida];
        this.bytesBloque = codificador.bytesBloque();
        this.bloqueAdpcm = new byte[bytesBloque];
    }

    /**
     * Empieza a usar una conexión: lo próximo que se envía es CONFIG + TRACK, y el primer AUDIO lleva
     * el seq siguiente a {@link EscritorTramas#ultimoSeqAudio()} (0 si la conexión es nueva).
     */
    public void conectar(EscritorTramas escritor, Estados estados) {
        this.escritor = escritor;
        this.estados = estados;
        configEnviado = false;
        seqEnviado = escritor.ultimoSeqAudio();
        seqCorte = Seq.siguiente(seqEnviado);
        nivelPublicadoMs = 0;
    }

    /** Fin de la conexión (la fuente sigue para la próxima). */
    public void desconectar() {
        escritor = null;
        estados = null;
    }

    /** "Detener": el próximo paso envía CONTROL DETENER y el pipeline termina. */
    public void pedirDetener() {
        detenerPedido = true;
    }

    /**
     * El próximo paso envía CONTROL VACIAR y empieza de cero: descarta el bloque a medio armar y
     * reinicia el códec y los filtros, como al empezar una pista (nada de antes se cuela después).
     */
    public void pedirVaciar() {
        vaciarPedido = true;
    }

    /**
     * Envía lo que toque, como mucho un AUDIO. Devuelve 0 si conviene llamar de nuevo enseguida,
     * los ms a esperar (mejor: hasta un STATE nuevo, que puede llegar antes), o {@link #FIN}.
     */
    public long paso(long ahoraNs) throws IOException {
        if (terminado) {
            return FIN;
        }
        if (escritor == null) {
            throw new IllegalStateException("pipeline sin conexión");
        }
        if (detenerPedido) {
            escritor.control(Protocolo.CONTROL_DETENER);
            terminado = true;
            return FIN;
        }
        if (!configEnviado) {
            escritor.config(config);
            if (track != null) {
                escritor.track(track);
            }
            configEnviado = true;
        }
        if (vaciarPedido) {
            vaciarPedido = false;
            escritor.control(Protocolo.CONTROL_VACIAR);
            seqCorte = Seq.siguiente(seqEnviado);
            llenos = 0;
            arrastre = 0;
            codificador.reiniciar();
            for (int i = 0; i < decimadores.length; i++) {
                decimadores[i].reiniciar();
            }
        }

        long nivel = nivelMs();
        nivelPublicadoMs = nivel;
        if (nivel >= config.bufferObjetivoMs) {
            return ESPERA_LLENO_MS;
        }
        if (!fuenteTerminada) {
            llenarBloque();
        }
        if (llenos < config.muestrasPorBloque && !fuenteTerminada) {
            return ESPERA_SIN_DATOS_MS;
        }
        if (llenos == 0) {
            terminado = true; // la fuente terminó y ya se envió todo
            return FIN;
        }
        codificador.codificarBloque(bloquePcm, 0, llenos, bloqueAdpcm, 0); // el último se rellena con 0
        llenos = 0;
        long seq = Seq.siguiente(seqEnviado);
        escritor.audio(seq, bloqueAdpcm, 0, bytesBloque);
        seqEnviado = seq;
        bloquesEnviados++;
        return 0;
    }

    /** Nivel estimado del buffer del receptor, en ms (ver la descripción de la clase). */
    long nivelMs() {
        Estados fuenteEstados = estados;
        Estado e = fuenteEstados != null ? fuenteEstados.ultimo() : null;
        if (e != null) {
            int trasCorte = Seq.distancia(e.ultimoSeq, seqCorte);
            int enVuelo = Seq.distancia(seqEnviado, e.ultimoSeq);
            // Con muchos bloques en vuelo, el nivel ya supera el objetivo: no hace falta otro tope.
            if (trasCorte >= 0 && enVuelo >= 0) {
                return e.bufferMs + config.msParaBloques(enVuelo);
            }
        }
        // Sin un STATE posterior al corte: todo lo enviado desde el corte sigue en el receptor.
        return config.msParaBloques(Seq.distancia(seqEnviado, seqCorte) + 1);
    }

    /** Llena {@link #bloquePcm} hasta un bloque con lo que la fuente tenga ahora. */
    private void llenarBloque() {
        int mpb = config.muestrasPorBloque;
        int multiplo = 1 << etapas;
        while (llenos < mpb) {
            int pedir = ((mpb - llenos) << etapas) - arrastre;
            int n = fuente.leer(entrada, arrastre, pedir);
            if (n < 0) {
                fuenteTerminada = true; // lo que quede en "arrastre" (< 2^etapas frames) se pierde
                return;
            }
            if (n == 0) {
                return;
            }
            if (n > pedir) {
                throw new IllegalStateException("la fuente entregó " + n + " frames de " + pedir);
            }
            int total = arrastre + n;
            // Cada etapa recibe un número par de frames y entrega exactamente la mitad.
            int usar = total & -multiplo;
            arrastre = total - usar;
            if (usar > 0) {
                if (canalesSalida == 1 && canalesFuente > 1) {
                    Downmix.aMono(entrada, 0, usar, canalesFuente, entrada, 0);
                }
                int f = usar;
                for (int i = 0; i < decimadores.length; i++) {
                    f = decimadores[i].procesar(entrada, 0, f, entrada, 0);
                }
                System.arraycopy(entrada, 0, bloquePcm, llenos * canalesSalida, f * canalesSalida);
                llenos += f;
                if (arrastre > 0) {
                    System.arraycopy(entrada, usar * canalesFuente, entrada, 0, arrastre * canalesFuente);
                }
            }
        }
    }

    public Config config() {
        return config;
    }

    public Preset preset() {
        return preset;
    }

    public Track track() {
        return track;
    }

    /** Último nivel estimado (para la UI), en ms. */
    public long nivelEstimadoMs() {
        return nivelPublicadoMs;
    }

    /** AUDIO enviados desde que se creó el pipeline (todas las conexiones). */
    public long bloquesEnviados() {
        return bloquesEnviados;
    }

    /** seq del último AUDIO de esta conexión ({@link Protocolo#SEQ_NINGUNO} si ninguno). */
    public long seqEnviado() {
        return seqEnviado;
    }

    public boolean terminado() {
        return terminado;
    }
}
