package cl.parlantey.emisor

/**
 * Presets de calidad (CLAUDE.md), con la tasa de origen más exigente (48 kHz).
 * Fase 0: solo sirve para leer la prueba de enlace; la elección real llega en la Fase 3.
 */
enum class Preset(val nombre: String, private val canales: Int, private val tasaHz: Int) {
    ALTA("Alta", 2, 48_000),
    NORMAL("Normal", 1, 48_000),
    SEGURA("Segura", 1, 24_000);

    /**
     * KB/s que ocupa el flujo en el enlace: bloques IMA ADPCM de 1024 muestras
     * (4 bytes de estado + 512 bytes por canal) dentro de tramas AUDIO
     * (cabecera de 8 bytes + seq u32). Ver PROTOCOLO.md.
     */
    val kBpsFlujo: Double
        get() {
            val bytesTrama = 8 + 4 + canales * (4 + MUESTRAS_POR_BLOQUE / 2)
            return tasaHz.toDouble() / MUESTRAS_POR_BLOQUE * bytesTrama / 1000.0
        }

    /** Throughput medido necesario: el flujo debe ser ≤ 70 % de lo medido. */
    val kBpsNecesarios: Double
        get() = kBpsFlujo / FRACCION_MAXIMA

    companion object {
        private const val MUESTRAS_POR_BLOQUE = 1024
        private const val FRACCION_MAXIMA = 0.70

        /** Preset Auto: el más alto que alcanza con lo medido, o null si ni Segura alcanza. */
        fun auto(kBpsMedidos: Double): Preset? = entries.firstOrNull { kBpsMedidos >= it.kBpsNecesarios }
    }
}
