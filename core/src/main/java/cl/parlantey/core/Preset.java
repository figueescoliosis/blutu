package cl.parlantey.core;

/**
 * Presets de calidad (CLAUDE.md). "Auto" no es un preset: es elegir uno con {@link #elegirAuto}.
 */
public enum Preset {
    ALTA("Alta", 2, false),
    NORMAL("Normal", 1, false),
    SEGURA("Segura", 1, true);

    /** El flujo debe ocupar como mucho esta fracción del throughput medido. */
    public static final double FRACCION_MAXIMA = 0.70;

    /** Tasa de origen con la que se comparan los presets en la prueba de enlace (la más exigente). */
    public static final int TASA_REFERENCIA = 48000;

    public final String nombre;
    public final int canales;
    /** Segura divide la tasa de origen por 2 (además de bajarla a ≤ 48 kHz). */
    public final boolean mitadDeTasa;

    Preset(String nombre, int canales, boolean mitadDeTasa) {
        this.nombre = nombre;
        this.canales = canales;
        this.mitadDeTasa = mitadDeTasa;
    }

    /**
     * Etapas de {@link DecimadorX2} para una tasa de origen. Segura no divide por 2 si la tasa
     * quedaría bajo {@link Protocolo#TASA_MIN}.
     */
    public int etapas(int tasaOrigen) {
        int etapas = DecimadorX2.etapasPara(tasaOrigen);
        if (mitadDeTasa && (tasaOrigen >> (etapas + 1)) >= Protocolo.TASA_MIN) {
            etapas++;
        }
        return etapas;
    }

    /** Canales que se envían: estéreo solo con Alta y una fuente estéreo; si no, downmix a mono. */
    public int canalesSalida(int canalesOrigen) {
        return canales == 2 && canalesOrigen == 2 ? 2 : 1;
    }

    public int tasaSalida(int tasaOrigen) {
        return tasaOrigen >> etapas(tasaOrigen);
    }

    /** KB/s que ocupa el flujo en el enlace: tramas AUDIO completas de 1024 muestras. */
    public double kBpsFlujo(int tasaOrigen) {
        int bytesTrama = Protocolo.LARGO_CABECERA + 4 + ImaAdpcm.bytesBloque(canales, Protocolo.MUESTRAS_POR_BLOQUE);
        return tasaSalida(tasaOrigen) / (double) Protocolo.MUESTRAS_POR_BLOQUE * bytesTrama / 1000.0;
    }

    /** Throughput medido que hace falta para este preset (regla del 70 %). */
    public double kBpsNecesarios(int tasaOrigen) {
        return kBpsFlujo(tasaOrigen) / FRACCION_MAXIMA;
    }

    /**
     * Preset Auto: el más alto que alcanza con lo medido (a {@link #TASA_REFERENCIA}). Sin
     * medición ({@code NaN}) devuelve NORMAL; si ni Segura alcanza, null.
     */
    public static Preset elegirAuto(double kBpsMedidos) {
        if (Double.isNaN(kBpsMedidos)) {
            return NORMAL;
        }
        for (Preset p : values()) {
            if (kBpsMedidos >= p.kBpsNecesarios(TASA_REFERENCIA)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Preset que corresponde a un CONFIG, para mostrarlo en el receptor: 2 canales → Alta;
     * 1 canal y ≥ 32 kHz → Normal; si no, Segura.
     */
    public static Preset deducir(int canales, int tasa) {
        if (canales >= 2) {
            return ALTA;
        }
        return tasa >= 32000 ? NORMAL : SEGURA;
    }
}
