package cl.parlantey.core;

import java.io.IOException;

/**
 * El audio que el emisor envía por una conexión, pista tras pista. Escribe en orden CONFIG, TRACK,
 * CONTROL y AUDIO, regulado por el control de flujo con STATE. Sobrevive a los cambios de pista y a
 * las reconexiones ({@link #conectar}). Lo usa un solo hilo, que llama a {@link #paso} en bucle.
 *
 * <p><b>Cambios de pista</b> (PROTOCOLO.md):
 * <ul>
 * <li>{@link #reproducir} con {@code vaciar}: VACIAR → CONFIG (si cambia el formato) → TRACK → AUDIO.</li>
 * <li>Fin natural de una pista con {@link #siguiente} del mismo formato: sin pausa, sin VACIAR ni
 * CONFIG; el TRACK sale cuando el receptor termina de sonar la anterior.</li>
 * <li>Fin natural con otro formato: FIN; cuando el receptor terminó de sonar, CONFIG → TRACK → AUDIO.</li>
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
    private CodificadorPista siguiente;
    private Track trackSiguiente;
    /** Pista que todavía suena en el receptor tras un cambio sin pausa (hasta enviar el TRACK). */
    private CodificadorPista previa;
    private long framesPrevia;
    private boolean trackPendiente;
    private boolean trackDiferido;
    private long cambiosDePista;
    private boolean pausado;

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
        vaciarPendiente = false;
        controlPendiente = pausado ? Protocolo.CONTROL_PAUSA : 0; // la pausa es por conexión
        nivelPublicadoMs = 0;
        if (fase == ESPERANDO_VACIO) {
            pasarASiguiente(false); // el receptor de la conexión nueva ya está vacío
        } else if (fase == SONANDO && actual != null) {
            previa = null;
            trackPendiente = trackActual != null;
            trackDiferido = false;
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
        previa = null;
        siguiente = null;
        trackSiguiente = null;
        ponerActual(pista, track, false);
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
        previa = null;
        trackActual = null;
        trackSiguiente = null;
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
                return ESPERA_LLENO_MS;
            }
            pasarASiguiente(false);
        }

        if (!configActual.equals(configEnviado)) {
            if (configEnviado != null && !actual.mismoFormato(configEnviado)) {
                // Otro formato: el receptor empieza una generación nueva y descarta lo encolado.
                seqCorte = Seq.siguiente(seqEnviado);
            }
            escritor.config(configActual);
            configEnviado = configActual;
        }
        if (trackPendiente && (!trackDiferido || nivel - configActual.msParaFrames(framesActual)
                <= configActual.msParaBloques(1))) {
            // Diferido: cuando lo que queda en el receptor ya es todo de esta pista.
            escritor.track(trackActual);
            trackPendiente = false;
            previa = null;
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
        seqEnviado = seq;
        bloquesEnviados++;
        framesActual += n;
        return 0;
    }

    private void ponerActual(CodificadorPista pista, Track track, boolean diferido) {
        actual = pista;
        trackActual = track;
        configActual = pista.config(objetivoMs);
        framesActual = 0;
        trackPendiente = track != null;
        trackDiferido = diferido;
    }

    /** Fin natural con la siguiente del mismo formato: sigue sin pausa ni CONFIG. */
    private void cambioSinPausa() {
        previa = actual;
        framesPrevia = framesActual;
        ponerActual(siguiente, trackSiguiente, true);
        siguiente = null;
        trackSiguiente = null;
        fase = SONANDO;
        cambiosDePista++;
    }

    /** El receptor terminó de sonar (o la conexión es nueva): empieza la siguiente, con su CONFIG. */
    private void pasarASiguiente(boolean diferido) {
        previa = null;
        ponerActual(siguiente, trackSiguiente, diferido);
        siguiente = null;
        trackSiguiente = null;
        fase = SONANDO;
        cambiosDePista++;
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

    /** true si en el receptor todavía suena la pista anterior (cambio sin pausa en curso). */
    public boolean sonandoPrevia() {
        return previa != null;
    }

    /** Lo que ya sonó de la pista que suena ahora en el receptor, estimado, en ms. */
    public long posicionMs() {
        Config c = configActual;
        if (c == null) {
            return 0;
        }
        long nivel = nivelMs();
        if (previa != null) {
            long msNueva = c.msParaFrames(framesActual);
            return Math.max(0, c.msParaFrames(framesPrevia) - Math.max(0, nivel - msNueva));
        }
        return Math.max(0, c.msParaFrames(framesActual) - nivel);
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

    /** seq del último AUDIO de esta conexión ({@link Protocolo#SEQ_NINGUNO} si ninguno). */
    public long seqEnviado() {
        return seqEnviado;
    }
}
