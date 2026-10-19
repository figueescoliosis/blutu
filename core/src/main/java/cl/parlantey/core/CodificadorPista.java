package cl.parlantey.core;

/**
 * Una pista lista para enviar: PCM16 de una {@link Fuente} → downmix y decimación según el
 * {@link Preset} → {@link CodificadorIma} → bloques ADPCM de {@link Protocolo#MUESTRAS_POR_BLOQUE}
 * frames en el formato de salida. Empieza con el códec en cero (como toda pista, PROTOCOLO.md §6).
 * Lo usa un solo hilo; no asigna memoria por bloque.
 */
public final class CodificadorPista {

    /** Lo que devuelve {@link #siguiente} cuando la pista terminó y ya no queda nada. */
    public static final int FIN = -1;

    private final Fuente fuente;
    private final Preset preset;
    private final boolean enVivo;
    private final int canalesFuente;
    private final int canalesSalida;
    private final int tasaSalida;
    private final int etapas;
    private final int mpb = Protocolo.MUESTRAS_POR_BLOQUE;
    private final DecimadorX2[] decimadores;
    private final CodificadorIma codificador;
    private final short[] entrada;
    private final short[] bloquePcm;

    /** Frames de entrada que sobraron (menos de 2^etapas), al inicio de {@link #entrada}. */
    private int arrastre;
    /** Frames ya procesados en {@link #bloquePcm}. */
    private int llenos;
    private boolean fuenteTerminada;
    private long framesEntregados;

    /**
     * @throws IllegalArgumentException si la fuente tiene una tasa o canales que no se pueden enviar
     */
    public CodificadorPista(Fuente fuente, Preset preset) {
        this(fuente, preset, false);
    }

    /**
     * @param enVivo la fuente es una captura en vivo: sus CONFIG llevan {@link Protocolo#FLAG_EN_VIVO}
     * @throws IllegalArgumentException si la fuente tiene una tasa o canales que no se pueden enviar
     */
    public CodificadorPista(Fuente fuente, Preset preset, boolean enVivo) {
        this.fuente = fuente;
        this.preset = preset;
        this.enVivo = enVivo;
        this.canalesFuente = fuente.canales();
        if (canalesFuente < 1 || canalesFuente > Downmix.MAX_CANALES_ENTRADA) {
            throw new IllegalArgumentException("canales de la fuente fuera de rango: " + canalesFuente);
        }
        int tasa = fuente.tasa();
        this.etapas = preset.etapas(tasa);
        this.canalesSalida = preset.canalesSalida(canalesFuente);
        this.tasaSalida = tasa >> etapas;
        Config.imaAdpcm(tasaSalida, canalesSalida, 0); // valida la tasa de salida
        this.decimadores = new DecimadorX2[etapas];
        for (int i = 0; i < etapas; i++) {
            decimadores[i] = new DecimadorX2(canalesSalida);
        }
        this.codificador = new CodificadorIma(canalesSalida, mpb);
        this.entrada = new short[(mpb << etapas) * canalesFuente];
        this.bloquePcm = new short[mpb * canalesSalida];
    }

    public Fuente fuente() {
        return fuente;
    }

    public Preset preset() {
        return preset;
    }

    public boolean enVivo() {
        return enVivo;
    }

    public int tasaSalida() {
        return tasaSalida;
    }

    public int canalesSalida() {
        return canalesSalida;
    }

    /** CONFIG de esta pista con el objetivo dado. */
    public Config config(long objetivoMs) {
        return Config.imaAdpcm(tasaSalida, canalesSalida, objetivoMs, enVivo);
    }

    /** true si la salida de esta pista tiene el formato de {@code c} (tasa, canales y bloque). */
    public boolean mismoFormato(Config c) {
        return c != null && c.sampleRate == tasaSalida && c.canales == canalesSalida && c.muestrasPorBloque == mpb;
    }

    public int bytesBloque() {
        return codificador.bytesBloque();
    }

    /** Frames de salida ya entregados en bloques (sin el relleno del último). */
    public long framesEntregados() {
        return framesEntregados;
    }

    /**
     * Arma y codifica el próximo bloque en {@code destino} (desde 0). Devuelve sus frames válidos (el
     * último bloque de la pista se completa con silencio), 0 si por ahora la fuente no tiene datos
     * (lo armado se conserva), o {@link #FIN} si la pista terminó y no queda nada.
     */
    public int siguiente(byte[] destino) {
        if (!fuenteTerminada) {
            llenar();
        }
        if (llenos < mpb && !fuenteTerminada) {
            return 0;
        }
        if (llenos == 0) {
            return FIN;
        }
        int validos = llenos;
        codificador.codificarBloque(bloquePcm, 0, validos, destino, 0);
        llenos = 0;
        framesEntregados += validos;
        return validos;
    }

    /** ms de audio de salida que faltan para completar el bloque en armado. */
    public long msParaCompletar() {
        return (long) (mpb - llenos) * 1000L / tasaSalida;
    }

    /** Descarta lo armado y reinicia filtros y códec (VACIAR sobre la misma fuente). */
    public void reiniciar() {
        llenos = 0;
        arrastre = 0;
        codificador.reiniciar();
        for (int i = 0; i < decimadores.length; i++) {
            decimadores[i].reiniciar();
        }
    }

    /** Llena {@link #bloquePcm} hasta un bloque con lo que la fuente tenga ahora. */
    private void llenar() {
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
}
