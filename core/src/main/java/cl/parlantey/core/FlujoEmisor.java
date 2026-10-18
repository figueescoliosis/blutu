package cl.parlantey.core;

import java.io.IOException;

/**
 * El audio que el emisor envía por una conexión, pista tras pista. Escribe en orden CONTROL, TRACK,
 * CONFIG y AUDIO, regulado por el control de flujo con STATE. Sobrevive a los cambios de pista y a
 * las reconexiones ({@link #conectar}). Lo usa un solo hilo, que llama a {@link #paso} en bucle.
 *
 * <p><b>Cambios de pista</b> (PROTOCOLO.md):
 * <ul>
 * <li>{@link #reproducir} con {@code vaciar}: VACIAR → TRACK → CONFIG (si cambia el formato) → AUDIO.</li>
 * <li>Fin natural de una pista con {@link #siguiente} del mismo formato: sin pausa, sin VACIAR ni
 * CONFIG; el TRACK sale cuando el receptor termina de sonar la anterior (le queda menos de un
 * bloque de ella). Para eso lleva las pistas que el receptor tiene en el buffer ("tramos").</li>
 * <li>Fin natural con otro formato: FIN; cuando el receptor terminó de sonar, TRACK → CONFIG → AUDIO.</li>
 * <li>Fin de la última pista: FIN.</li>
 * </ul>
 *
 * <p><b>Control de flujo:</b> {@code nivel = STATE.bufferMs + Seq.distancia(seqEnviado,
 * STATE.ultimoSeq) × ms por bloque}; se envía mientras {@code nivel < objetivo}. Tras un corte
 * (conexión nueva o VACIAR), el STATE se ignora hasta que informe un {@code ultimoSeq} enviado después
 * del corte: hasta entonces el nivel es todo lo enviado desde el corte.
 */
public final class FlujoEmisor {

    /** Último STATE de la conexión actual (null si todavía no llega ninguno). */
    public interface Estados {
        Estado ultimo();
    }

    /** Lo que devuelve {@link #paso} cuando no hay nada que enviar ni que esperar. */
    public static final long FIN = -1;
    /** Buffer objetivo para archivos y el tono de prueba (CLAUDE.md). */
    public static final long OBJETIVO_ARCHIVOS_MS = 2000;

    /** Nivel ≥ objetivo: esperar un STATE nuevo (cada 250 ms) o hasta este tiempo. */
    static final long ESPERA_LLENO_MS = 50;
    static final long ESPERA_SIN_DATOS_MS = 5;
    /**
     * Tras un FIN, se da por vacío al receptor aunque el STATE no lo confirme: nivel + este margen
     * (sin contar el tiempo en pausa).
     */
    static final long MARGEN_VACIO_MS = 3000;
    /**
     * Pistas que puede haber a la vez en el buffer del receptor. Con más (pistas de menos de
     * objetivo / 16), el TRACK de alguna sale antes de tiempo; el audio no cambia.
     */
    public static final int MAX_TRAMOS = 16;

    private static final int SONANDO = 0;
    private static final int ESPERANDO_VACIO = 1;
    private static final int TERMINADO = 2;
    private static final int DETENIDO = 3;

    private final long objetivoMs;
    private final byte[] bloque = new byte[ImaAdpcm.bytesBloque(Protocolo.MAX_CANALES, Protocolo.MUESTRAS_POR_BLOQUE)];
    private int fase = DETENIDO;

    // --- Pistas.
    private CodificadorPista actual;
    private Track trackActual;
    private Config configActual;
    private long framesActual;
    /** seq del primer AUDIO de la pista actual ({@link Protocolo#SEQ_NINGUNO} si todavía no sale). */
    private long seqInicioActual = Protocolo.SEQ_NINGUNO;
    private CodificadorPista siguiente;
    private Track trackSiguiente;
    private long cambiosDePista;
    /** Número de la pista actual: cuenta cada {@link #reproducir} y cada cambio de pista. */
    private long numeroActual;
    private boolean pausado;

    // --- Tramos: las pistas de esta generación en el receptor, de la que suena a la que se envía.
    // Una generación empieza con cada corte (conexión, VACIAR, CONFIG de otro formato).
    /** Frames (en bloques completos) enviados en esta generación. */
    private long framesGeneracion;
    private final long[] tramoInicio = new long[MAX_TRAMOS]; // en frames de la generación
    private final long[] tramoBase = new long[MAX_TRAMOS]; // frames de la pista enviados antes del tramo
    private final long[] tramoNumero = new long[MAX_TRAMOS];
    private final Track[] tramoTrack = new Track[MAX_TRAMOS];
    private int tramoPrimero;
    private int tramos;
    /** El TRACK del primer tramo (el que suena) todavía no se envió. */
    private boolean trackPendiente;

    // --- Pedidos para el próximo paso.
    private int controlPendiente;
    private boolean vaciarPendiente;
    private boolean detenerPendiente;

    // --- FIN enviado.
    private long seqFin;
    private long finDesdeNs;
    private long nivelEnFinMs;
    /** STATE consecutivos que muestran al receptor vacío tras el FIN (hacen falta 2). */
    private int vaciosSeguidos;
    private Estado ultimoVisto;
    private long pausaDesdeNs;
    private long pausaAcumuladaNs;

    // --- Conexión actual.
    private EscritorTramas escritor;
    private Estados estados;
    private Config configEnviado;
    private long seqEnviado = Protocolo.SEQ_NINGUNO;
    private long seqCorte;
    private long nivelPublicadoMs;
    private long bloquesEnviados;

    /** @param objetivoMs bufferObjetivoMs de los CONFIG y del control de flujo */
    public FlujoEmisor(long objetivoMs) {
        this.objetivoMs = objetivoMs;
    }

    // --- Conexión.

    /**
     * Empieza a usar una conexión (nueva o reconexión). Si hay una pista en curso, lo próximo es
     * CONFIG + TRACK + su audio desde donde va; el seq sigue a {@link EscritorTramas#ultimoSeqAudio()}.
     */
    public void conectar(EscritorTramas escritor, Estados estados) {
        this.escritor = escritor;
        this.estados = estados;
        configEnviado = null;
        seqEnviado = escritor.ultimoSeqAudio();
        seqCorte = Seq.siguiente(seqEnviado);
        seqInicioActual = Protocolo.SEQ_NINGUNO;
        vaciarPendiente = false;
        controlPendiente = pausado ? Protocolo.CONTROL_PAUSA : 0; // la pausa es por conexión
        nivelPublicadoMs = 0;
        if (fase == ESPERANDO_VACIO) {
            pasarASiguiente(); // el receptor de la conexión nueva ya está vacío
        } else if (fase == SONANDO && actual != null) {
            nuevaGeneracion(); // lo que tenía el receptor se perdió: suena la actual desde ya
        } else if (fase == TERMINADO) {
            vaciosSeguidos = 2; // no hay nada que esperar que suene
            framesGeneracion = 0;
            tramos = 0;
            trackPendiente = false;
        }
    }

    /** Fin de la conexión (las pistas siguen para la próxima). */
    public void desconectar() {
        escritor = null;
        estados = null;
    }

    // --- Pistas y comandos (los aplica el próximo paso).

    /**
     * Empieza {@code pista} ya. Con {@code vaciar}, lo que el receptor tenga de antes se descarta
     * (VACIAR); sin, se agrega detrás (p. ej. tras una reconexión, con el receptor vacío).
     */
    public void reproducir(CodificadorPista pista, Track track, boolean vaciar) {
        if (vaciar && escritor != null && Seq.distancia(seqEnviado, seqCorte) >= 0) {
            vaciarPendiente = true;
        }
        detenerPendiente = false;
        siguiente = null;
        trackSiguiente = null;
        ponerActual(pista, track);
        nuevaGeneracion(); // tras VACIAR (o en un receptor vacío) suena esta desde ya
        fase = SONANDO;
    }

    /** La pista que sigue cuando termine la actual (null: ninguna, al terminar se envía FIN). */
    public void siguiente(CodificadorPista pista, Track track) {
        siguiente = pista;
        trackSiguiente = pista != null ? track : null;
    }

    public void pausar() {
        if (!pausado) {
            pausado = true;
            controlPendiente = Protocolo.CONTROL_PAUSA;
            pausaDesdeNs = 0; // lo fija el próximo paso
        }
    }

    public void reanudar() {
        if (pausado) {
            pausado = false;
            controlPendiente = Protocolo.CONTROL_REANUDAR;
        }
    }

    /** DETENER: el receptor descarta todo y olvida el CONFIG; no queda ninguna pista. */
    public void detener() {
        detenerPendiente = escritor != null;
        actual = null;
        siguiente = null;
        trackActual = null;
        trackSiguiente = null;
        tramos = 0;
        trackPendiente = false;
        fase = DETENIDO;
    }

    // --- Paso.

    /**
     * Envía lo que toque, como mucho un AUDIO. Devuelve 0 si conviene llamar de nuevo enseguida,
     * los ms a esperar (mejor: hasta un STATE nuevo, que puede llegar antes), o {@link #FIN} si no hay
     * nada que enviar: sin pista, o con la última pista terminada y ya sonada en el receptor.
     */
    public long paso(long ahoraNs) throws IOException {
        if (escritor == null) {
            throw new IllegalStateException("flujo sin conexión");
        }
        if (detenerPendiente) {
            detenerPendiente = false;
            escritor.control(Protocolo.CONTROL_DETENER);
            configEnviado = null; // el receptor lo olvida
            return FIN;
        }
        if (controlPendiente != 0) {
            escritor.control(controlPendiente);
            controlPendiente = 0;
        }
        if (pausado) {
            if (pausaDesdeNs == 0) {
                pausaDesdeNs = ahoraNs;
            }
        } else if (pausaDesdeNs != 0) {
            pausaAcumuladaNs += ahoraNs - pausaDesdeNs; // la espera tras un FIN no corre en pausa
            pausaDesdeNs = 0;
        }
        if (fase == DETENIDO) {
            return FIN;
        }
        if (vaciarPendiente) {
            vaciarPendiente = false;
            escritor.control(Protocolo.CONTROL_VACIAR);
            seqCorte = Seq.siguiente(seqEnviado);
        }
        long nivel = nivelMs();
        nivelPublicadoMs = nivel;
        if (fase == TERMINADO) {
            if (siguiente == null) {
                avanzarTramos(nivel);
                return vacio(ahoraNs) ? FIN : ESPERA_LLENO_MS;
            }
            // Llegó una pista más después del FIN.
            if (siguiente.mismoFormato(configActual)) {
                cambioSinPausa();
            } else {
                fase = ESPERANDO_VACIO;
            }
        }
        if (fase == ESPERANDO_VACIO) {
            if (!vacio(ahoraNs)) {
                avanzarTramos(nivel); // las pistas cortas que quedan en el receptor también tienen TRACK
                return ESPERA_LLENO_MS;
            }
            pasarASiguiente();
        }

        avanzarTramos(nivel); // TRACK antes que CONFIG (CLAUDE.md: VACIAR → TRACK → CONFIG → audio)
        if (!configActual.equals(configEnviado)) {
            if (configEnviado != null && !actual.mismoFormato(configEnviado)) {
                // Otro formato: el receptor empieza una generación nueva y descarta lo encolado.
                seqCorte = Seq.siguiente(seqEnviado);
            }
            escritor.config(configActual);
            configEnviado = configActual;
        }
        if (nivel >= objetivoMs) {
            return ESPERA_LLENO_MS;
        }
        int n = actual.siguiente(bloque);
        if (n == 0) {
            return ESPERA_SIN_DATOS_MS;
        }
        if (n == CodificadorPista.FIN) {
            if (siguiente != null && siguiente.mismoFormato(configActual)) {
                cambioSinPausa();
                return 0;
            }
            escritor.control(Protocolo.CONTROL_FIN);
            seqFin = seqEnviado;
            finDesdeNs = ahoraNs;
            nivelEnFinMs = nivel;
            vaciosSeguidos = 0;
            ultimoVisto = null;
            pausaAcumuladaNs = 0;
            pausaDesdeNs = pausado ? ahoraNs : 0;
            fase = siguiente != null ? ESPERANDO_VACIO : TERMINADO;
            return ESPERA_LLENO_MS;
        }
        long seq = Seq.siguiente(seqEnviado);
        escritor.audio(seq, bloque, 0, actual.bytesBloque());
        if (seqInicioActual == Protocolo.SEQ_NINGUNO) {
            seqInicioActual = seq;
        }
        seqEnviado = seq;
        bloquesEnviados++;
        framesActual += n;
        framesGeneracion += Protocolo.MUESTRAS_POR_BLOQUE; // el receptor suena el bloque entero
        return 0;
    }

    private void ponerActual(CodificadorPista pista, Track track) {
        actual = pista;
        trackActual = track;
        configActual = pista.config(objetivoMs);
        framesActual = 0;
        seqInicioActual = Protocolo.SEQ_NINGUNO;
        numeroActual++;
    }

    /** Fin natural con la siguiente del mismo formato: sigue sin pausa ni CONFIG. */
    private void cambioSinPausa() {
        ponerActual(siguiente, trackSiguiente);
        siguiente = null;
        trackSiguiente = null;
        fase = SONANDO;
        cambiosDePista++;
        if (tramos == MAX_TRAMOS) {
            sacarTramo(); // demasiadas pistas cortas: el TRACK de la que sigue sale antes
        }
        agregarTramo();
    }

    /** El receptor terminó de sonar (o la conexión es nueva): empieza la siguiente, con su CONFIG. */
    private void pasarASiguiente() {
        ponerActual(siguiente, trackSiguiente);
        siguiente = null;
        trackSiguiente = null;
        fase = SONANDO;
        cambiosDePista++;
        nuevaGeneracion();
    }

    /** El receptor empieza vacío: el único tramo es la actual (desde donde va) y su TRACK sale ya. */
    private void nuevaGeneracion() {
        framesGeneracion = 0;
        tramos = 0;
        agregarTramo();
    }

    private void agregarTramo() {
        if (tramos == 0) {
            trackPendiente = trackActual != null; // es la que suena
        }
        int i = (tramoPrimero + tramos) % MAX_TRAMOS;
        tramoInicio[i] = framesGeneracion;
        tramoBase[i] = framesActual;
        tramoNumero[i] = numeroActual;
        tramoTrack[i] = trackActual;
        tramos++;
    }

    private void sacarTramo() {
        tramoTrack[tramoPrimero] = null;
        tramoPrimero = (tramoPrimero + 1) % MAX_TRAMOS;
        tramos--;
        trackPendiente = tramoTrack[tramoPrimero] != null;
    }

    /** Frames de la generación que el receptor ya sonó, estimados con el nivel. */
    private long framesOidos(long nivel) {
        return framesGeneracion - nivel * configActual.sampleRate / 1000;
    }

    /**
     * Saca los tramos que ya sonaron y envía el TRACK de la pista que empieza a sonar: cuando al
     * receptor le queda menos de un bloque de la anterior.
     */
    private void avanzarTramos(long nivel) throws IOException {
        if (tramos >= 2) {
            long oidos = framesOidos(nivel) + Protocolo.MUESTRAS_POR_BLOQUE;
            while (tramos >= 2 && tramoInicio[(tramoPrimero + 1) % MAX_TRAMOS] <= oidos) {
                sacarTramo();
            }
        }
        if (trackPendiente) {
            escritor.track(tramoTrack[tramoPrimero]);
            trackPendiente = false;
        }
    }

    /**
     * true si el receptor ya sonó todo hasta el FIN: dos STATE seguidos con todo recibido y el buffer
     * en 0 (uno solo puede quedarse corto en un bloque que el hilo de audio está escribiendo). O si
     * pasó el tiempo de sobra para sonarlo, sin contar las pausas.
     */
    private boolean vacio(long ahoraNs) {
        Estado e = estados != null ? estados.ultimo() : null;
        if (e != null && e != ultimoVisto) {
            ultimoVisto = e;
            vaciosSeguidos = e.ultimoSeq == seqFin && e.bufferMs == 0 ? vaciosSeguidos + 1 : 0;
        }
        if (vaciosSeguidos >= 2) {
            return true;
        }
        if (pausado) {
            return false;
        }
        long transcurrido = ahoraNs - finDesdeNs - pausaAcumuladaNs;
        return transcurrido > (nivelEnFinMs + MARGEN_VACIO_MS) * 1000000L;
    }

    /** Nivel estimado del buffer del receptor, en ms (ver la descripción de la clase). */
    long nivelMs() {
        Config c = configActual;
        if (c == null) {
            return 0;
        }
        Estados fuenteEstados = estados;
        Estado e = fuenteEstados != null ? fuenteEstados.ultimo() : null;
        if (e != null) {
            int trasCorte = Seq.distancia(e.ultimoSeq, seqCorte);
            int enVuelo = Seq.distancia(seqEnviado, e.ultimoSeq);
            // Con muchos bloques en vuelo, el nivel ya supera el objetivo: no hace falta otro tope.
            if (trasCorte >= 0 && enVuelo >= 0) {
                return e.bufferMs + c.msParaBloques(enVuelo);
            }
        }
        // Sin un STATE posterior al corte: todo lo enviado desde el corte sigue en el receptor.
        return c.msParaBloques(Seq.distancia(seqEnviado, seqCorte) + 1);
    }

    // --- Consultas (mismo hilo).

    /** La pista que se está enviando (null si ninguna). */
    public CodificadorPista actual() {
        return actual;
    }

    public Track trackActual() {
        return trackActual;
    }

    /** true si en el receptor todavía suena una pista anterior a la actual (cambio sin pausa en curso). */
    public boolean sonandoPrevia() {
        return tramos >= 2;
    }

    /**
     * Número de la pista que suena en el receptor (ver {@link #numeroActual}); el de la actual si no
     * se sabe.
     */
    public long numeroSonando() {
        return tramos > 0 ? tramoNumero[tramoPrimero] : numeroActual;
    }

    /** Número de la pista actual: sube con cada {@link #reproducir} y cada cambio de pista. */
    public long numeroActual() {
        return numeroActual;
    }

    /** Lo que ya sonó de la pista que suena ahora en el receptor, estimado, en ms desde su inicio. */
    public long posicionMs() {
        Config c = configActual;
        if (c == null || tramos == 0) {
            return 0;
        }
        long frames = framesOidos(nivelMs()) - tramoInicio[tramoPrimero] + tramoBase[tramoPrimero];
        return Math.max(0, c.msParaFrames(frames));
    }

    /** Cuántas veces la pista actual pasó a la siguiente sola (fin natural). */
    public long cambiosDePista() {
        return cambiosDePista;
    }

    public boolean pausado() {
        return pausado;
    }

    /** true si la última pista terminó (se envió FIN); el receptor puede estar sonando el final. */
    public boolean terminado() {
        return fase == TERMINADO;
    }

    public boolean detenido() {
        return fase == DETENIDO;
    }

    /** true si envió FIN y espera que el receptor termine de sonar para pasar a otro formato. */
    boolean esperandoVacio() {
        return fase == ESPERANDO_VACIO;
    }

    /** CONFIG de la pista actual (null si no hay). */
    public Config config() {
        return configActual;
    }

    public long nivelEstimadoMs() {
        return nivelPublicadoMs;
    }

    public long bloquesEnviados() {
        return bloquesEnviados;
    }

    /**
     * seq del primer AUDIO de la pista actual en esta conexión ({@link Protocolo#SEQ_NINGUNO} si
     * todavía no sale): un STATE con {@code ultimoSeq} desde ahí y sonando confirma que suena.
     */
    public long seqInicioActual() {
        return seqInicioActual;
    }

    /** seq del último AUDIO de esta conexión ({@link Protocolo#SEQ_NINGUNO} si ninguno). */
    public long seqEnviado() {
        return seqEnviado;
    }
}
